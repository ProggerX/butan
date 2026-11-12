package dev.butan.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.activity.ComponentActivity
import dev.butan.core.ButanTag
import dev.butan.core.NfcFrame
import java.io.ByteArrayOutputStream

/**
 * A reader-side exchange with another phone.
 *
 * The reader first reads the other phone's bundle, then writes its own bundle
 * back over the same ISO-DEP session using UPDATE BINARY. This makes one touch
 * bidirectional: the other phone only has to emulate a card.
 */
class NfcGateway(private val activity: ComponentActivity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private var reading = false

    @Volatile
    private var readProgressListener: ((read: Int, total: Int) -> Unit)? = null

    private var discoveryTimeout: Runnable? = null

    val available: Boolean
        get() = adapter != null

    val enabled: Boolean
        get() = adapter?.isEnabled == true

    val isReading: Boolean
        get() = reading

    fun startExchange(
        outgoingFrame: ByteArray,
        discoveryTimeoutMs: Long,
        onResult: (ExchangeResult) -> Unit,
        onError: (message: String, recoverable: Boolean) -> Unit,
        onReadProgress: (read: Int, total: Int) -> Unit = { _, _ -> },
        onWriteProgress: (read: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val nfc = adapter
        if (nfc == null) {
            onError("This phone has no NFC hardware.", false)
            return
        }
        if (!nfc.isEnabled) {
            onError("NFC is off. Turn it on in system settings.", false)
            return
        }

        stopReading()
        reading = true
        readProgressListener = onReadProgress

        val timeout = Runnable {
            if (!reading) return@Runnable
            reading = false
            readProgressListener = null
            discoveryTimeout = null
            runCatching { nfc.disableReaderMode(activity) }
            Log.d(TAG, "discovery timeout")
            onError("No phone found. Keep both apps open and touch the phones again.", true)
        }
        discoveryTimeout = timeout

        try {
            nfc.enableReaderMode(
                activity,
                { tag ->
                    // A card was found; the exchange may take longer than discovery.
                    activity.window.decorView.removeCallbacks(timeout)
                    discoveryTimeout = null

                    val result = exchangeWithTag(tag, outgoingFrame, onWriteProgress)
                    activity.runOnUiThread {
                        if (!reading) return@runOnUiThread
                        reading = false
                        readProgressListener = null
                        runCatching { nfc.disableReaderMode(activity) }
                        if (result == null) {
                            onError("Couldn't complete a Butan exchange. Retrying…", true)
                        } else {
                            onResult(result)
                        }
                    }
                },
                READER_FLAGS,
                null,
            )
            activity.window.decorView.postDelayed(timeout, discoveryTimeoutMs)
            Log.d(TAG, "reader mode enabled")
        } catch (exception: Exception) {
            reading = false
            readProgressListener = null
            discoveryTimeout = null
            Log.d(TAG, "enableReaderMode failed", exception)
            onError("Could not start the NFC reader.", false)
        }
    }

    fun stopReading() {
        discoveryTimeout?.let { activity.window.decorView.removeCallbacks(it) }
        discoveryTimeout = null
        if (!reading) {
            readProgressListener = null
            return
        }
        reading = false
        readProgressListener = null
        runCatching { adapter?.disableReaderMode(activity) }
        Log.d(TAG, "reader mode disabled")
    }

    private fun exchangeWithTag(
        tag: Tag,
        outgoingFrame: ByteArray,
        onWriteProgress: (read: Int, total: Int) -> Unit,
    ): ExchangeResult? {
        Log.d(TAG, "tag tech=${tag.techList.joinToString()}")
        val isoDep = IsoDep.get(tag) ?: run {
            Log.d(TAG, "tag has no IsoDep technology")
            return null
        }

        return try {
            isoDep.connect()
            isoDep.timeout = TRANSFER_TIMEOUT_MS
            Log.d(TAG, "IsoDep connected")

            if (!selectAid(isoDep)) {
                Log.d(TAG, "select AID failed")
                return null
            }

            val remote = readFrameOnce(isoDep) ?: run {
                Log.d(TAG, "remote frame read failed")
                return null
            }

            val writeOk = writeFrame(isoDep, outgoingFrame, onWriteProgress)
            Log.d(TAG, "exchange read=${remote.second.size} writeOk=$writeOk")
            ExchangeResult(remote.first, remote.second, writeOk)
        } catch (exception: Exception) {
            Log.d(TAG, "exchange failed", exception)
            null
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private fun readFrameOnce(isoDep: IsoDep): Pair<Int, ByteArray>? {
        val headerResponse = transceiveData(isoDep, readBinary(0, NfcFrame.HEADER_SIZE)) ?: run {
            Log.d(TAG, "header read failed")
            return null
        }
        if (headerResponse.status != SW_OK) {
            Log.d(TAG, "header read status=${"%04x".format(headerResponse.status)}")
            return null
        }

        val headerData = headerResponse.data
        Log.d(TAG, "header response=${headerData.toHex()}")

        val header = NfcFrame.parseHeader(headerData) ?: run {
            Log.d(TAG, "invalid frame header")
            return null
        }
        Log.d(TAG, "kind=${header.kind} length=${header.length}")

        val total = NfcFrame.HEADER_SIZE + header.length
        readProgressListener?.invoke(headerData.size, total)

        val payload = ByteArrayOutputStream(header.length)
        val extra = if (headerData.size > NfcFrame.HEADER_SIZE) {
            headerData.copyOfRange(NfcFrame.HEADER_SIZE, headerData.size)
        } else {
            ByteArray(0)
        }

        val fromExtra = minOf(extra.size, header.length)
        if (fromExtra > 0) payload.write(extra, 0, fromExtra)

        val remaining = header.length - fromExtra
        if (remaining > 0) {
            val rest = readExact(
                isoDep = isoDep,
                offset = NfcFrame.HEADER_SIZE + fromExtra,
                length = remaining,
                total = total,
                alreadyRead = headerData.size,
            ) ?: run {
                Log.d(TAG, "payload read failed")
                return null
            }
            payload.write(rest)
        }

        val bytes = payload.toByteArray()
        if (bytes.size != header.length) {
            Log.d(TAG, "short payload: ${bytes.size}/${header.length}")
            return null
        }

        return header.kind to bytes
    }

    private fun readExact(
        isoDep: IsoDep,
        offset: Int,
        length: Int,
        total: Int,
        alreadyRead: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream(length)
        var position = offset
        var remaining = length
        var read = alreadyRead

        while (remaining > 0) {
            val chunk = minOf(READ_CHUNK, remaining)
            val response = transceiveData(isoDep, readBinary(position, chunk)) ?: return null
            Log.d(
                TAG,
                "READ offset=$position length=$chunk -> data=${response.data.size} " +
                    "sw=${"%04x".format(response.status)}",
            )
            if (response.status != SW_OK) return null

            val data = response.data
            if (data.isEmpty()) return null

            val take = minOf(data.size, remaining)
            output.write(data, 0, take)
            position += take
            remaining -= take
            read += take
            readProgressListener?.invoke(read, total)
        }

        return output.toByteArray()
    }

    private fun writeFrame(
        isoDep: IsoDep,
        frame: ByteArray,
        onProgress: (read: Int, total: Int) -> Unit,
    ): Boolean {
        var offset = 0
        while (offset < frame.size) {
            val chunk = minOf(WRITE_CHUNK, frame.size - offset)
            val apdu = byteArrayOf(
                0x80.toByte(),
                0xd6.toByte(),
                (offset ushr 8).toByte(),
                (offset and 0xff).toByte(),
                chunk.toByte(),
            ) + frame.copyOfRange(offset, offset + chunk)

            val response = transceiveData(isoDep, apdu) ?: return false
            if (response.status != SW_OK) {
                Log.d(TAG, "WRITE offset=$offset failed sw=${"%04x".format(response.status)}")
                return false
            }

            offset += chunk
            onProgress(offset, frame.size)
            Log.d(TAG, "WRITE offset=$offset/${frame.size}")
        }
        return true
    }

    private fun selectAid(isoDep: IsoDep): Boolean {
        repeat(SELECT_ATTEMPTS) { attempt ->
            val response = transceiveData(isoDep, selectAidCommand())
            Log.d(TAG, "SELECT -> sw=${response?.status?.let { "%04x".format(it) }}")

            if (response?.status == SW_OK) return true
            if (attempt < SELECT_ATTEMPTS - 1) Thread.sleep(SELECT_RETRY_DELAY_MS)
        }
        return false
    }

    private fun transceiveData(isoDep: IsoDep, apdu: ByteArray): ApduResponse? {
        val response = isoDep.transceive(apdu)
        if (response.size < 2) return null
        return ApduResponse(
            data = response.copyOfRange(0, response.size - 2),
            status = statusOf(response),
        )
    }

    private fun selectAidCommand(): ByteArray {
        val aid = ButanTag.AID
        return byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid
    }

    private fun readBinary(offset: Int, length: Int): ByteArray = byteArrayOf(
        0x00,
        0xb0.toByte(),
        (offset ushr 8).toByte(),
        (offset and 0xff).toByte(),
        length.toByte(),
    )

    private fun statusOf(response: ByteArray): Int {
        if (response.size < 2) return -1
        return ((response[response.size - 2].toInt() and 0xff) shl 8) or
            (response[response.size - 1].toInt() and 0xff)
    }

    private fun ByteArray.toHex(limit: Int = 64): String {
        val shown = if (size <= limit) this else copyOf(limit)
        val suffix = if (size > limit) "…(${size})" else ""
        return shown.joinToString(separator = "") { "%02x".format(it) } + suffix
    }

    data class ExchangeResult(
        val kind: Int,
        val payload: ByteArray,
        val writeOk: Boolean,
    )

    private data class ApduResponse(val data: ByteArray, val status: Int)

    private companion object {
        const val TAG = "ButanNfc"
        const val READ_CHUNK = 240
        const val WRITE_CHUNK = 240
        const val TRANSFER_TIMEOUT_MS = 5000
        const val SW_OK = 0x9000
        const val SELECT_ATTEMPTS = 4
        const val SELECT_RETRY_DELAY_MS = 100L

        const val READER_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}
