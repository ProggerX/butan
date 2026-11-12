package dev.butan.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SignedContent(
    val v: Int = 1,
    val from: String,
    val fromName: String,
    val signKey: String,
    val ts: Long,
    val body: String,
)

@Serializable
data class Message(
    val content: SignedContent,
    val sig: String,
)

@Serializable
data class ContactContent(
    val v: Int = 1,
    val name: String,
    val age: String,
    val signKey: String,
)

@Serializable
data class ContactCard(
    val content: ContactContent,
    val sig: String,
)

sealed interface OpenResult {
    data class ForMe(val message: Message) : OpenResult
    data object NotForMe : OpenResult
    data object Invalid : OpenResult
}

/**
 * Application envelope format: an age file whose plaintext is a signed JSON message.
 * Contact cards are self-signed JSON records exchanged in Butan NFC frames.
 */
object Butan {
    const val CONTACT_MIME = "application/vnd.butan.contact"
    const val ENVELOPE_MIME = "application/vnd.butan.envelope"

    const val MAX_BODY_CHARS = 3000
    const val MAX_BODY_BYTES = 3000
    const val MAX_NAME_CHARS = 48

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun createEnvelope(
        identity: Identity,
        recipient: String,
        fromName: String,
        body: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): ByteArray {
        require(body.length <= MAX_BODY_CHARS) { "message body is too long" }
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
            "message body is too many bytes"
        }
        require(Identity.isValidRecipient(recipient)) { "invalid recipient" }
        require(nowSeconds > 0) { "invalid timestamp" }

        val content = SignedContent(
            from = identity.ageRecipient,
            fromName = sanitizeName(fromName),
            signKey = Crypto.base64Encode(identity.signingPublicKey),
            ts = nowSeconds,
            body = body,
        )

        val message = Message(
            content = content,
            sig = Crypto.base64Encode(identity.sign(serialize(content))),
        )

        val plaintext = json.encodeToString(Message.serializer(), message)
            .toByteArray(Charsets.UTF_8)

        return Age.encrypt(recipient, plaintext)
    }

    fun openEnvelope(identity: Identity, envelope: ByteArray): OpenResult {
        return when (val result = Age.decrypt(identity, envelope)) {
            AgeResult.NotForMe -> OpenResult.NotForMe
            AgeResult.Invalid -> OpenResult.Invalid
            is AgeResult.ForMe -> {
                val message = runCatching {
                    json.decodeFromString(Message.serializer(), result.plaintext.toString(Charsets.UTF_8))
                }.getOrNull() ?: return OpenResult.Invalid

                if (validateMessage(message)) OpenResult.ForMe(message) else OpenResult.Invalid
            }
        }
    }

    fun createContactCard(identity: Identity, name: String): ByteArray {
        val content = ContactContent(
            name = sanitizeName(name),
            age = identity.ageRecipient,
            signKey = Crypto.base64Encode(identity.signingPublicKey),
        )
        val card = ContactCard(
            content = content,
            sig = Crypto.base64Encode(identity.sign(serialize(content))),
        )
        return json.encodeToString(ContactCard.serializer(), card).toByteArray(Charsets.UTF_8)
    }

    fun openContactCard(bytes: ByteArray): ContactCard? {
        val card = runCatching {
            json.decodeFromString(ContactCard.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null

        val content = card.content
        if (content.v != 1) return null
        if (content.name.length > MAX_NAME_CHARS) return null
        if (!Identity.isValidRecipient(content.age)) return null

        val signKey = Crypto.base64Decode(content.signKey) ?: return null
        val signature = Crypto.base64Decode(card.sig) ?: return null
        if (signKey.size != 32 || signature.size != 64) return null
        if (!Crypto.ed25519Verify(signKey, serialize(content), signature)) return null

        return card
    }

    fun envelopeToText(envelope: ByteArray): String = Crypto.base64Encode(envelope)

    fun envelopeFromText(text: String): ByteArray? = Crypto.base64Decode(text)

    fun envelopeFingerprint(envelope: ByteArray): String = Crypto.sha256Hex(envelope)

    private fun validateMessage(message: Message): Boolean {
        val content = message.content
        if (content.v != 1) return false
        if (content.body.length > MAX_BODY_CHARS) return false
        if (content.body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) return false
        if (content.fromName.length > MAX_NAME_CHARS) return false
        if (content.ts <= 0) return false
        if (!Identity.isValidRecipient(content.from)) return false

        val signKey = Crypto.base64Decode(content.signKey) ?: return false
        val signature = Crypto.base64Decode(message.sig) ?: return false
        if (signKey.size != 32 || signature.size != 64) return false

        return Crypto.ed25519Verify(signKey, serialize(content), signature)
    }

    private fun sanitizeName(name: String): String =
        name.trim().take(MAX_NAME_CHARS).ifBlank { "Anonymous" }

    private fun serialize(content: SignedContent): ByteArray =
        json.encodeToString(SignedContent.serializer(), content).toByteArray(Charsets.UTF_8)

    private fun serialize(content: ContactContent): ByteArray =
        json.encodeToString(ContactContent.serializer(), content).toByteArray(Charsets.UTF_8)
}
