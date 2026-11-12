package dev.butan.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import dev.butan.core.ButanTag

/**
 * Exposes the currently armed Butan frame over a private ISO-DEP AID.
 *
 * Selecting this AID bypasses Android's NDEF stack and vendor NDEF handlers
 * (for example Xiaomi Mi Connect/Mi Share), which otherwise race the transfer.
 */
class ButanHostApduService : HostApduService() {
    private var armed: NfcSlot.Armed? = null
    private var tag: ButanTag? = null
    private var delivered = false
    private var incomingDelivered = false

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val current = NfcSlot.current() ?: run {
            Log.d(TAG, "APDU with no armed frame: ${commandApdu.toHex()}")
            return status(0x6a82)
        }

        if (current !== armed) {
            armed = current
            tag = runCatching { ButanTag(current.frame) }.getOrNull()
            delivered = false
            incomingDelivered = false
            Log.d(TAG, "using armed frame size=${current.frame.size}")
            if (tag == null) return status(0x6700)
        }

        val emulator = tag ?: return status(0x6700)
        val response = emulator.processCommandApdu(commandApdu)

        Log.d(
            TAG,
            "APDU ${commandApdu.toHex()} -> ${response.toHex(24)} " +
                "read=${emulator.bytesRead}/${current.frame.size} full=${emulator.isFullyRead}",
        )

        current.onProgress?.invoke(emulator.bytesRead, current.frame.size)
        if (emulator.writeTotal > 0) {
            current.onWriteProgress?.invoke(emulator.writeProgress, emulator.writeTotal)
        }

        val incoming = emulator.incomingFrame
        if (incoming != null && !incomingDelivered) {
            incomingDelivered = true
            Log.d(TAG, "incoming frame complete size=${incoming.size}")
            current.onIncoming?.invoke(incoming)
        }

        if (emulator.isFullyRead && !delivered) {
            delivered = true
            Log.d(TAG, "frame fully read, notifying sender")
            current.onDelivered?.invoke()
        }

        return response
    }

    override fun onDeactivated(reason: Int) {
        tag?.resetSession()
        incomingDelivered = false
    }

    private fun status(value: Int): ByteArray = byteArrayOf(
        (value ushr 8).toByte(),
        (value and 0xff).toByte(),
    )

    private fun ByteArray.toHex(limit: Int = 64): String {
        val shown = if (size <= limit) this else copyOf(limit)
        val suffix = if (size > limit) "…(${size})" else ""
        return shown.joinToString(separator = "") { "%02x".format(it) } + suffix
    }

    private companion object {
        const val TAG = "ButanHce"
    }
}
