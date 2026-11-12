package dev.butan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Bech32Test {
    @Test
    fun `spec key pair round trips`() {
        val secret = "AGE-SECRET-KEY-1GFPYYSJZGFPYYSJZGFPYYSJZGFPYYSJZGFPYYSJZGFPYYSJZGFPQ4EGAEX"
        val recipient = "age1zvkyg2lqzraa2lnjvqej32nkuu0ues2s82hzrye869xeexvn73equnujwj"

        val decoded = Bech32.decode(secret)
        assertNotNull(decoded)
        assertEquals("age-secret-key-", decoded!!.first)

        val identity = Identity.fromSeed(decoded.second)
        assertEquals(recipient, identity.ageRecipient)
        assertEquals(secret, identity.ageSecret)

        val recipientDecoded = Bech32.decode(recipient)
        assertNotNull(recipientDecoded)
        assertEquals("age", recipientDecoded!!.first)
        assertEquals(32, recipientDecoded.second.size)
    }

    @Test
    fun `uppercase and lowercase decode alike`() {
        val lower = "age1zvkyg2lqzraa2lnjvqej32nkuu0ues2s82hzrye869xeexvn73equnujwj"
        val upper = lower.uppercase()

        val a = Bech32.decode(lower)
        val b = Bech32.decode(upper)
        assertNotNull(a)
        assertNotNull(b)
        assertArrayEquals(a!!.second, b!!.second)
    }

    @Test
    fun `mixed case and bad checksums are rejected`() {
        assertNull(Bech32.decode("Age1zvkyg2lqzraa2lnjvqej32nkuu0ues2s82hzrye869xeexvn73equnujwj"))
        assertNull(Bech32.decode("age1zvkyg2lqzraa2lnjvqej32nkuu0ues2s82hzrye869xeexvn73equnujwx"))
        assertNull(Bech32.decode("not-bech32"))
        assertNull(Bech32.decode(""))
    }
}
