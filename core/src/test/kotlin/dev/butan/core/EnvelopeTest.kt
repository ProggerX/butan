package dev.butan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class EnvelopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `signed envelope opens and preserves fields`() {
        val alice = Identity.generate()
        val bob = Identity.generate()

        val envelope = Butan.createEnvelope(
            identity = alice,
            recipient = bob.ageRecipient,
            fromName = "Alice",
            body = "meet at the coffee shop",
            nowSeconds = 1_700_000_000,
        )

        val opened = Butan.openEnvelope(bob, envelope)
        assertTrue(opened is OpenResult.ForMe)
        val message = (opened as OpenResult.ForMe).message
        assertEquals(alice.ageRecipient, message.content.from)
        assertEquals("Alice", message.content.fromName)
        assertEquals("meet at the coffee shop", message.content.body)
        assertEquals(1_700_000_000, message.content.ts)
        assertEquals(Crypto.base64Encode(alice.signingPublicKey), message.content.signKey)
    }

    @Test
    fun `envelope for someone else is not for me`() {
        val alice = Identity.generate()
        val bob = Identity.generate()
        val carol = Identity.generate()

        val envelope = Butan.createEnvelope(
            identity = alice,
            recipient = bob.ageRecipient,
            fromName = "Alice",
            body = "hi bob",
        )

        assertEquals(OpenResult.NotForMe, Butan.openEnvelope(carol, envelope))
    }

    @Test
    fun `forged signature is rejected`() {
        val alice = Identity.generate()
        val bob = Identity.generate()

        val forgedContent = SignedContent(
            from = alice.ageRecipient,
            fromName = "Mallory",
            signKey = Crypto.base64Encode(alice.signingPublicKey),
            ts = 1_700_000_000,
            body = "forged",
        )
        val forgedMessage = Message(
            content = forgedContent,
            sig = Crypto.base64Encode(ByteArray(64)),
        )
        val plaintext = json.encodeToString(Message.serializer(), forgedMessage)
            .toByteArray(Charsets.UTF_8)
        val envelope = Age.encrypt(bob.ageRecipient, plaintext)

        assertEquals(OpenResult.Invalid, Butan.openEnvelope(bob, envelope))
    }

    @Test
    fun `contact cards round trip and verify`() {
        val alice = Identity.generate()
        val bytes = Butan.createContactCard(alice, "Alice")

        val card = Butan.openContactCard(bytes)
        assertNotNull(card)
        assertEquals("Alice", card!!.content.name)
        assertEquals(alice.ageRecipient, card.content.age)
        assertEquals(Crypto.base64Encode(alice.signingPublicKey), card.content.signKey)
    }

    @Test
    fun `tampered contact card is rejected`() {
        val alice = Identity.generate()
        val card = json.decodeFromString(
            ContactCard.serializer(),
            Butan.createContactCard(alice, "Alice").toString(Charsets.UTF_8),
        )
        val tampered = card.copy(content = card.content.copy(name = "Mallory"))
        val bytes = json.encodeToString(ContactCard.serializer(), tampered)
            .toByteArray(Charsets.UTF_8)

        assertNull(Butan.openContactCard(bytes))
    }

    @Test
    fun `envelope text helpers round trip`() {
        val alice = Identity.generate()
        val envelope = Age.encrypt(alice.ageRecipient, "hello".toByteArray())
        val text = Butan.envelopeToText(envelope)

        assertTrue(text.isNotBlank())
        assertTrue(Butan.envelopeFromText(text)!!.contentEquals(envelope))
        assertNull(Butan.envelopeFromText("not base64!"))
    }
}
