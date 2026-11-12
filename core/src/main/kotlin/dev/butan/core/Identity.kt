package dev.butan.core

/**
 * Local age X25519 identity plus a deterministic Ed25519 signing key.
 *
 * The 32-byte seed is the age secret key. The signing seed is derived from it with
 * HKDF so a single recovery value restores both keys.
 */
class Identity private constructor(seed: ByteArray) {
    val seed: ByteArray = seed.copyOf()
    val ageRecipient: String
    val ageSecret: String
    val signingPublicKey: ByteArray
    val fingerprint: String

    internal val signingSeed: ByteArray

    init {
        require(this.seed.size == 32) { "age identity must be 32 bytes" }

        ageRecipient = Bech32.encode("age", Crypto.x25519PublicKey(this.seed))
        ageSecret = Bech32.encode("AGE-SECRET-KEY-", this.seed, uppercase = true)
        signingSeed = Crypto.hkdf(
            this.seed,
            info = "butan/v1/ed25519".toByteArray(Charsets.US_ASCII),
        )
        signingPublicKey = Crypto.ed25519PublicKey(signingSeed)
        fingerprint = Crypto.sha256(ageRecipient.toByteArray(Charsets.US_ASCII))
            .take(6)
            .joinToString(separator = "") { "%02x".format(it) }
    }

    internal fun sign(message: ByteArray): ByteArray = Crypto.ed25519Sign(signingSeed, message)

    companion object {
        fun generate(): Identity = Identity(Crypto.randomBytes(32))

        fun fromSeed(seed: ByteArray): Identity {
            require(seed.size == 32) { "age identity must be 32 bytes" }
            return Identity(seed.copyOf())
        }

        fun isValidRecipient(value: String): Boolean {
            val decoded = Bech32.decode(value) ?: return false
            return decoded.first == "age" && decoded.second.size == 32
        }
    }
}
