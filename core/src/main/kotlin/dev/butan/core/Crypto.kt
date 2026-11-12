package dev.butan.core

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object Crypto {
    val random = SecureRandom()
    val empty = ByteArray(0)
    val zeroNonce = ByteArray(12)

    private val base64Encoder = Base64.getEncoder().withoutPadding()
    private val base64Decoder = Base64.getDecoder()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String =
        sha256(data).joinToString(separator = "") { "%02x".format(it) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val output = ByteArray(mac.macSize)
        mac.doFinal(output, 0)
        return output
    }

    fun hkdf(
        inputKeyMaterial: ByteArray,
        salt: ByteArray = empty,
        info: ByteArray = empty,
        length: Int = 32,
    ): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }

    fun x25519PublicKey(secret: ByteArray): ByteArray {
        val publicKey = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(secret, 0, publicKey, 0)
        return publicKey
    }

    /** Returns null for a low-order peer point (RFC 7748 all-zero rejection). */
    fun x25519Agreement(secret: ByteArray, peerPublicKey: ByteArray): ByteArray? {
        val sharedSecret = ByteArray(X25519.POINT_SIZE)
        val ok = X25519.calculateAgreement(secret, 0, peerPublicKey, 0, sharedSecret, 0)
        return if (ok) sharedSecret else null
    }

    fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, empty))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val processed = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        val finished = cipher.doFinal(output, processed)
        return if (processed + finished == output.size) output else output.copyOf(processed + finished)
    }

    fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, empty))
        return try {
            val output = ByteArray(cipher.getOutputSize(ciphertext.size))
            val processed = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
            val finished = cipher.doFinal(output, processed)
            if (processed + finished == output.size) output else output.copyOf(processed + finished)
        } catch (_: Exception) {
            null
        }
    }

    fun ed25519PublicKey(seed: ByteArray): ByteArray {
        val publicKey = ByteArray(32)
        Ed25519.generatePublicKey(seed, 0, publicKey, 0)
        return publicKey
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signature = ByteArray(64)
        Ed25519.sign(seed, 0, message, 0, message.size, signature, 0)
        return signature
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return Ed25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
    }

    fun base64Encode(data: ByteArray): String = base64Encoder.encodeToString(data)

    fun base64Decode(input: String): ByteArray? {
        if ('=' in input) return null
        return try {
            val decoded = base64Decoder.decode(input)
            if (base64Encoder.encodeToString(decoded) != input) null else decoded
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
