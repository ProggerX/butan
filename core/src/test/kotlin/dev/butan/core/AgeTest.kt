package dev.butan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class AgeTest {
    private val random = SecureRandom()

    @Test
    fun `round trip empty payload`() {
        val identity = Identity.generate()
        val envelope = Age.encrypt(identity.ageRecipient, ByteArray(0))
        val result = Age.decrypt(identity, envelope)
        assertTrue(result is AgeResult.ForMe)
        assertArrayEquals(ByteArray(0), (result as AgeResult.ForMe).plaintext)
    }

    @Test
    fun `round trip small payload`() {
        val identity = Identity.generate()
        val plaintext = "hello butan".toByteArray()
        val envelope = Age.encrypt(identity.ageRecipient, plaintext)
        val result = Age.decrypt(identity, envelope)
        assertTrue(result is AgeResult.ForMe)
        assertArrayEquals(plaintext, (result as AgeResult.ForMe).plaintext)
    }

    @Test
    fun `round trip multiple stream chunks`() {
        val identity = Identity.generate()
        val sizes = listOf(64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 200_000)
        for (size in sizes) {
            val plaintext = ByteArray(size).also(random::nextBytes)
            val envelope = Age.encrypt(identity.ageRecipient, plaintext)
            val result = Age.decrypt(identity, envelope)
            assertTrue("size $size", result is AgeResult.ForMe)
            assertArrayEquals(plaintext, (result as AgeResult.ForMe).plaintext)
        }
    }

    @Test
    fun `wrong identity cannot decrypt`() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val envelope = Age.encrypt(alice.ageRecipient, "for alice".toByteArray())
        assertEquals(AgeResult.NotForMe, Age.decrypt(bob, envelope))
    }

    @Test
    fun `tampered payload is rejected`() {
        val identity = Identity.generate()
        val envelope = Age.encrypt(identity.ageRecipient, "hello".toByteArray())
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        assertEquals(AgeResult.Invalid, Age.decrypt(identity, envelope))
    }

    @Test
    fun `garbage is rejected`() {
        val identity = Identity.generate()
        assertEquals(AgeResult.Invalid, Age.decrypt(identity, ByteArray(0)))
        assertEquals(AgeResult.Invalid, Age.decrypt(identity, "not age".toByteArray()))
    }
}
