package dev.butan.core

import java.io.ByteArrayOutputStream

data class BundleEntry(val kind: Int, val payload: ByteArray)

data class SyncBundle(
    val entries: List<BundleEntry>,
    val hasMore: Boolean,
)

/**
 * Payload for a sync bundle carried inside a single NfcFrame:
 *
 *   version 1 byte
 *   flags   1 byte (bit 0 = hasMore)
 *   count   2 bytes
 *   entries: kind 1 byte, length 4 bytes, payload
 */
object BundleCodec {
    private const val VERSION = 1
    private const val FLAG_HAS_MORE = 1
    private const val HEADER_SIZE = 4
    private const val ENTRY_HEADER_SIZE = 5

    const val MAX_PAYLOAD_BYTES = NfcFrame.MAX_FRAME_SIZE - NfcFrame.HEADER_SIZE

    fun encodedSize(entries: List<BundleEntry>): Int =
        HEADER_SIZE + entries.sumOf { ENTRY_HEADER_SIZE + it.payload.size }

    fun encode(entries: List<BundleEntry>, hasMore: Boolean): ByteArray? {
        if (entries.size > 0xffff) return null
        if (encodedSize(entries) > MAX_PAYLOAD_BYTES) return null

        return ByteArrayOutputStream().use { output ->
            output.write(VERSION)
            output.write(if (hasMore) FLAG_HAS_MORE else 0)
            output.write((entries.size ushr 8) and 0xff)
            output.write(entries.size and 0xff)

            for (entry in entries) {
                output.write(entry.kind)
                val length = entry.payload.size
                output.write((length ushr 24) and 0xff)
                output.write((length ushr 16) and 0xff)
                output.write((length ushr 8) and 0xff)
                output.write(length and 0xff)
                output.write(entry.payload)
            }

            output.toByteArray()
        }
    }

    fun decode(payload: ByteArray): SyncBundle? {
        if (payload.size < HEADER_SIZE) return null
        if (payload[0].toInt() and 0xff != VERSION) return null

        val hasMore = (payload[1].toInt() and FLAG_HAS_MORE) != 0
        val count = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)

        var position = HEADER_SIZE
        val entries = ArrayList<BundleEntry>(count)

        repeat(count) {
            if (position + ENTRY_HEADER_SIZE > payload.size) return null
            val kind = payload[position].toInt() and 0xff
            if (kind != NfcFrame.KIND_ENVELOPE && kind != NfcFrame.KIND_CONTACT) return null
            val length = ((payload[position + 1].toInt() and 0xff) shl 24) or
                ((payload[position + 2].toInt() and 0xff) shl 16) or
                ((payload[position + 3].toInt() and 0xff) shl 8) or
                (payload[position + 4].toInt() and 0xff)
            if (length < 0 || length > MAX_PAYLOAD_BYTES) return null
            position += ENTRY_HEADER_SIZE
            if (position + length > payload.size) return null
            entries.add(BundleEntry(kind, payload.copyOfRange(position, position + length)))
            position += length
        }

        if (position != payload.size) return null
        return SyncBundle(entries, hasMore)
    }
}
