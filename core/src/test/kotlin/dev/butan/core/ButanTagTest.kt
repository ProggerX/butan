package dev.butan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButanTagTest {
    @Test
    fun `frame header round trips`() {
        val payload = ByteArray(1234) { (it and 0xff).toByte() }
        val frame = NfcFrame.encode(NfcFrame.KIND_ENVELOPE, payload)

        val header = NfcFrame.parseHeader(frame.copyOfRange(0, NfcFrame.HEADER_SIZE))
        assertNotNull(header)
        assertEquals(NfcFrame.KIND_ENVELOPE, header!!.kind)
        assertEquals(payload.size, header.length)
        assertEquals(Butan.ENVELOPE_MIME, NfcFrame.kindToMime(header.kind))

        assertNull(NfcFrame.parseHeader(ByteArray(NfcFrame.HEADER_SIZE)))
        assertNull(NfcFrame.kindToMime(99))
    }

    @Test
    fun `reads the whole frame with iso dep apdus`() {
        val payload = ByteArray(700) { (it and 0xff).toByte() }
        val frame = NfcFrame.encode(NfcFrame.KIND_CONTACT, payload)
        val tag = ButanTag(frame)

        assertStatus(0x9000, tag.processCommandApdu(selectAid()))
        assertFalse(tag.isFullyRead)

        val headerBytes = read(tag, 0, NfcFrame.HEADER_SIZE)
        val header = NfcFrame.parseHeader(headerBytes)
        assertNotNull(header)
        assertEquals(NfcFrame.KIND_CONTACT, header!!.kind)
        assertEquals(payload.size, header.length)

        val readPayload = read(tag, NfcFrame.HEADER_SIZE, header.length)
        assertArrayEquals(payload, readPayload)
        assertTrue(tag.isFullyRead)
    }


    @Test
    fun `accepts an inbound frame via update binary`() {
        val outgoing = NfcFrame.encode(NfcFrame.KIND_BUNDLE, ByteArray(32))
        val incoming = NfcFrame.encode(
            NfcFrame.KIND_BUNDLE,
            ByteArray(600) { (it + 7).toByte() },
        )
        val tag = ButanTag(outgoing)

        assertStatus(0x9000, tag.processCommandApdu(selectAid()))

        var offset = 0
        while (offset < incoming.size) {
            val chunk = minOf(240, incoming.size - offset)
            val apdu = updateBinary(offset, incoming.copyOfRange(offset, offset + chunk))
            assertStatus(0x9000, tag.processCommandApdu(apdu))
            offset += chunk
        }

        assertArrayEquals(incoming, tag.incomingFrame)
        assertEquals(incoming.size, tag.writeTotal)

        tag.resetSession()
        assertEquals(null, tag.incomingFrame)
        assertEquals(0, tag.writeTotal)
    }

    @Test
    fun `wrong aid and reads before select are rejected`() {
        val tag = ButanTag(NfcFrame.encode(NfcFrame.KIND_ENVELOPE, ByteArray(10)))

        assertStatus(0x6985, tag.processCommandApdu(readBinary(0, 16)))
        assertStatus(0x6a82, tag.processCommandApdu(selectAid(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))))
        assertStatus(0x6985, tag.processCommandApdu(readBinary(0, 16)))

        assertStatus(0x9000, tag.processCommandApdu(selectAid()))
        assertStatus(0x6b00, tag.processCommandApdu(readBinary(0xffff, 16)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized frame is rejected`() {
        ButanTag(ByteArray(NfcFrame.MAX_FRAME_SIZE + 1))
    }

    private fun read(tag: ButanTag, offset: Int, length: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(240, remaining)
            val response = tag.processCommandApdu(readBinary(position, chunk))
            assertStatus(0x9000, response)
            output.write(response, 0, response.size - 2)
            position += chunk
            remaining -= chunk
        }
        return output.toByteArray()
    }


    private fun updateBinary(offset: Int, data: ByteArray): ByteArray = byteArrayOf(
        0x80.toByte(),
        0xd6.toByte(),
        (offset ushr 8).toByte(),
        (offset and 0xff).toByte(),
        data.size.toByte(),
    ) + data

    private fun selectAid(aid: ByteArray = ButanTag.AID): ByteArray =
        byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid

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
