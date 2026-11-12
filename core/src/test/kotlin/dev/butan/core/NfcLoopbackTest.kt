package dev.butan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Simulates the two sides of one ISO-DEP session without radios:
 * the reader reads the card's bundle, then writes its own bundle back.
 */
class NfcLoopbackTest {
    @Test
    fun `one session exchanges bundles in both directions`() {
        val slaveBundle = SyncBundle(
            entries = listOf(
                BundleEntry(NfcFrame.KIND_CONTACT, "slave-contact".toByteArray()),
                BundleEntry(NfcFrame.KIND_ENVELOPE, "slave-envelope".toByteArray()),
            ),
            hasMore = false,
        )
        val slaveFrame = NfcFrame.encode(
            NfcFrame.KIND_BUNDLE,
            BundleCodec.encode(slaveBundle.entries, slaveBundle.hasMore)!!,
        )

        val masterBundle = SyncBundle(
            entries = listOf(
                BundleEntry(NfcFrame.KIND_CONTACT, "master-contact".toByteArray()),
                BundleEntry(NfcFrame.KIND_ENVELOPE, "master-envelope".toByteArray()),
            ),
            hasMore = true,
        )
        val masterFrame = NfcFrame.encode(
            NfcFrame.KIND_BUNDLE,
            BundleCodec.encode(masterBundle.entries, masterBundle.hasMore)!!,
        )

        val card = ButanTag(slaveFrame)

        assertStatus(0x9000, card.processCommandApdu(selectAid()))

        val remoteFrame = readFrame(card)
        assertArrayEquals(slaveFrame, remoteFrame)

        writeFrame(card, masterFrame)
        assertArrayEquals(masterFrame, card.incomingFrame)

        val remoteBundle = decodeBundle(remoteFrame)
        assertEquals(2, remoteBundle.entries.size)
        assertArrayEquals("slave-contact".toByteArray(), remoteBundle.entries[0].payload)
        assertArrayEquals("slave-envelope".toByteArray(), remoteBundle.entries[1].payload)
        assertEquals(false, remoteBundle.hasMore)

        val writtenBundle = decodeBundle(card.incomingFrame!!)
        assertEquals(2, writtenBundle.entries.size)
        assertArrayEquals("master-contact".toByteArray(), writtenBundle.entries[0].payload)
        assertArrayEquals("master-envelope".toByteArray(), writtenBundle.entries[1].payload)
        assertEquals(true, writtenBundle.hasMore)
    }

    private fun decodeBundle(frame: ByteArray): SyncBundle {
        val header = NfcFrame.parseHeader(frame)
        assertNotNull(header)
        assertEquals(NfcFrame.KIND_BUNDLE, header!!.kind)
        val payload = frame.copyOfRange(
            NfcFrame.HEADER_SIZE,
            NfcFrame.HEADER_SIZE + header.length,
        )
        val bundle = BundleCodec.decode(payload)
        assertNotNull(bundle)
        return bundle!!
    }

    private fun readFrame(card: ButanTag): ByteArray {
        val header = read(card, 0, NfcFrame.HEADER_SIZE)
        val parsed = NfcFrame.parseHeader(header)
        assertNotNull(parsed)
        val payload = read(card, NfcFrame.HEADER_SIZE, parsed!!.length)
        return header + payload
    }

    private fun read(card: ButanTag, offset: Int, length: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(240, remaining)
            val response = card.processCommandApdu(readBinary(position, chunk))
            assertStatus(0x9000, response)
            output.write(response, 0, response.size - 2)
            position += chunk
            remaining -= chunk
        }
        return output.toByteArray()
    }

    private fun writeFrame(card: ButanTag, frame: ByteArray) {
        var offset = 0
        while (offset < frame.size) {
            val chunk = minOf(240, frame.size - offset)
            val apdu = byteArrayOf(
                0x80.toByte(),
                0xd6.toByte(),
                (offset ushr 8).toByte(),
                (offset and 0xff).toByte(),
                chunk.toByte(),
            ) + frame.copyOfRange(offset, offset + chunk)
            assertStatus(0x9000, card.processCommandApdu(apdu))
            offset += chunk
        }
    }

    private fun selectAid(): ByteArray =
        byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x00, ButanTag.AID.size.toByte()) + ButanTag.AID

    private fun readBinary(offset: Int, length: Int): ByteArray = byteArrayOf(
        0x00,
        0xb0.toByte(),
        (offset ushr 8).toByte(),
        (offset and 0xff).toByte(),
        length.toByte(),
    )

    private fun assertStatus(expected: Int, response: ByteArray) {
        val actual = ((response[response.size - 2].toInt() and 0xff) shl 8) or
            (response[response.size - 1].toInt() and 0xff)
        assertEquals(expected, actual)
    }
}
