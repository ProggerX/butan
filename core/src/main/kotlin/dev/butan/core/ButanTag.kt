package dev.butan.core

/**
 * Butan NFC frame.
 *
 * The phones deliberately do NOT use NDEF. A private ISO-DEP AID is selected by
 * the receiver, which bypasses Android's NDEF stack and vendor handlers such as
 * Xiaomi Mi Connect/Mi Share.
 *
 * Layout (all integers big-endian):
 *
 *   magic  4 bytes  42 54 4E 01 ("BTN" + version 1)
 *   kind   1 byte   1 = envelope, 2 = contact card
 *   length 4 bytes  payload length
 *   payload
 */
object NfcFrame {
    const val HEADER_SIZE = 9
    const val MAX_FRAME_SIZE = 0xffff
    const val KIND_ENVELOPE = 1
    const val KIND_CONTACT = 2
    const val KIND_BUNDLE = 3

    private val MAGIC = byteArrayOf(0x42, 0x54, 0x4e, 0x01)

    data class Header(val kind: Int, val length: Int)

    fun encode(kind: Int, payload: ByteArray): ByteArray {
        require(kind == KIND_ENVELOPE || kind == KIND_CONTACT || kind == KIND_BUNDLE) {
            "unknown frame kind"
        }
        require(payload.size <= MAX_FRAME_SIZE - HEADER_SIZE) { "frame is too large" }

        return ByteArray(HEADER_SIZE + payload.size).also { frame ->
            MAGIC.copyInto(frame, 0)
            frame[4] = kind.toByte()
            frame[5] = (payload.size ushr 24).toByte()
            frame[6] = (payload.size ushr 16).toByte()
            frame[7] = (payload.size ushr 8).toByte()
            frame[8] = payload.size.toByte()
            payload.copyInto(frame, HEADER_SIZE)
        }
    }

    fun parseHeader(header: ByteArray): Header? {
        if (header.size < HEADER_SIZE) return null
        for (index in MAGIC.indices) {
            if (header[index] != MAGIC[index]) return null
        }

        val kind = header[4].toInt() and 0xff
        if (kind != KIND_ENVELOPE && kind != KIND_CONTACT && kind != KIND_BUNDLE) return null

        val length = ((header[5].toInt() and 0xff) shl 24) or
            ((header[6].toInt() and 0xff) shl 16) or
            ((header[7].toInt() and 0xff) shl 8) or
            (header[8].toInt() and 0xff)

        if (length < 0 || length > MAX_FRAME_SIZE - HEADER_SIZE) return null
        return Header(kind, length)
    }

    fun kindToMime(kind: Int): String? = when (kind) {
        KIND_ENVELOPE -> Butan.ENVELOPE_MIME
        KIND_CONTACT -> Butan.CONTACT_MIME
        else -> null
    }
}

/**
 * Minimal ISO-DEP card emulation for the Butan frame.
 *
 * The receiver only needs SELECT by AID and READ BINARY. READ BINARY uses the
 * P1/P2 two-byte offset, so frames are capped at 65535 bytes.
 */
class ButanTag(private val frame: ByteArray) {
    private var selected = false

    var isFullyRead: Boolean = false
        private set

    var bytesRead: Int = 0
        private set

    private val incomingBuffer = ByteArray(NfcFrame.MAX_FRAME_SIZE)
    private var incomingPosition = 0
    private var incomingTotal = -1

    var incomingFrame: ByteArray? = null
        private set

    val writeProgress: Int
        get() = incomingPosition

    val writeTotal: Int
        get() = if (incomingTotal > 0) incomingTotal else 0

    init {
        require(frame.size <= NfcFrame.MAX_FRAME_SIZE) { "frame is too large" }
    }

    fun processCommandApdu(commandApdu: ByteArray): ByteArray {
        if (commandApdu.size < 4) return status(SW_WRONG_LENGTH)

        val cla = commandApdu[0].toInt() and 0xff
        val ins = commandApdu[1].toInt() and 0xff
        val p1 = commandApdu[2].toInt() and 0xff
        val p2 = commandApdu[3].toInt() and 0xff

        return when {
            cla == 0x00 && ins == 0xa4 && p1 == 0x04 -> {
                val data = commandData(commandApdu)
                if (data.contentEquals(AID)) {
                    selected = true
                    status(SW_OK)
                } else {
                    selected = false
                    status(SW_FILE_NOT_FOUND)
                }
            }

            cla == 0x80 && ins == 0xd6 -> {
                if (!selected) return status(SW_CONDITIONS_NOT_SATISFIED)

                val length = commandApdu.getOrNull(4)?.toInt()?.and(0xff) ?: 0
                if (length == 0 || commandApdu.size < 5 + length) {
                    return status(SW_WRONG_LENGTH)
                }
                val data = commandApdu.copyOfRange(5, 5 + length)
                val offset = (p1 shl 8) or p2

                if (offset < incomingPosition) {
                    if (offset + length > incomingPosition) return status(SW_WRONG_OFFSET)
                    for (index in data.indices) {
                        if (incomingBuffer[offset + index] != data[index]) {
                            return status(SW_WRONG_LENGTH)
                        }
                    }
                    return status(SW_OK)
                }
                if (offset != incomingPosition) return status(SW_WRONG_OFFSET)
                if (offset + length > NfcFrame.MAX_FRAME_SIZE) return status(SW_WRONG_LENGTH)

                data.copyInto(incomingBuffer, offset)
                incomingPosition += length

                if (incomingTotal < 0 && incomingPosition >= NfcFrame.HEADER_SIZE) {
                    val header = NfcFrame.parseHeader(
                        incomingBuffer.copyOfRange(0, NfcFrame.HEADER_SIZE),
                    )
                    val total = if (header != null) NfcFrame.HEADER_SIZE + header.length else -1
                    if (total <= 0 || total > NfcFrame.MAX_FRAME_SIZE) {
                        resetIncoming()
                        return status(SW_WRONG_LENGTH)
                    }
                    incomingTotal = total
                }

                if (incomingTotal > 0 && incomingPosition >= incomingTotal && incomingFrame == null) {
                    incomingFrame = incomingBuffer.copyOfRange(0, incomingTotal)
                }

                status(SW_OK)
            }

            cla == 0x00 && ins == 0xb0 -> {
                if (!selected) return status(SW_CONDITIONS_NOT_SATISFIED)

                val offset = (p1 shl 8) or p2
                if (offset >= frame.size) return status(SW_WRONG_OFFSET)

                val requested = if (commandApdu.size > 4) {
                    (commandApdu[4].toInt() and 0xff).let { if (it == 0) 256 else it }
                } else {
                    256
                }
                val end = minOf(offset + requested, frame.size)
                val body = frame.copyOfRange(offset, end)

                if (end > bytesRead) bytesRead = end
                if (end >= frame.size) isFullyRead = true

                body + status(SW_OK)
            }

            else -> status(SW_INS_NOT_SUPPORTED)
        }
    }

    fun resetSession() {
        selected = false
        resetIncoming()
    }

    private fun resetIncoming() {
        incomingPosition = 0
        incomingTotal = -1
        incomingFrame = null
    }

    private fun commandData(apdu: ByteArray): ByteArray {
        val length = apdu.getOrNull(4)?.toInt()?.and(0xff) ?: 0
        if (length == 0 || apdu.size < 5 + length) return ByteArray(0)
        return apdu.copyOfRange(5, 5 + length)
    }

    private fun status(value: Int): ByteArray = byteArrayOf(
        (value ushr 8).toByte(),
        (value and 0xff).toByte(),
    )

    companion object {
        /**
         * Proprietary AID. Must match res/xml/apduservice.xml.
         * F0 42 55 54 41 4E 01 = proprietary class + "BUTAN" + version.
         */
        val AID = byteArrayOf(
            0xf0.toByte(), 0x42, 0x55, 0x54, 0x41, 0x4e, 0x01,
        )

        private const val SW_OK = 0x9000
        private const val SW_WRONG_LENGTH = 0x6700
        private const val SW_CONDITIONS_NOT_SATISFIED = 0x6985
        private const val SW_WRONG_OFFSET = 0x6b00
        private const val SW_FILE_NOT_FOUND = 0x6a82
        private const val SW_INS_NOT_SUPPORTED = 0x6d00
    }
}
