package dev.butan.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

sealed interface AgeResult {
    data class ForMe(val plaintext: ByteArray) : AgeResult
    data object NotForMe : AgeResult
    data object Invalid : AgeResult
}

/**
 * age v1 file format (X25519 recipient type) with a single recipient stanza.
 *
 * This is a compact implementation of https://c2sp.org/age. It is wire-compatible
 * with the reference `age` tool for binary (non-armored) X25519 files.
 */
object Age {
    private const val VERSION = "age-encryption.org/v1"
    private const val CHUNK_SIZE = 64 * 1024
    private const val TAG_SIZE = 16

    private val X25519_INFO = "age-encryption.org/v1/X25519".toByteArray(Charsets.US_ASCII)
    private val HEADER_INFO = "header".toByteArray(Charsets.US_ASCII)
    private val PAYLOAD_INFO = "payload".toByteArray(Charsets.US_ASCII)

    fun encrypt(recipient: String, plaintext: ByteArray): ByteArray {
        val decoded = Bech32.decode(recipient)
            ?: throw IllegalArgumentException("invalid age recipient")
        require(decoded.first == "age" && decoded.second.size == 32) {
            "invalid age recipient"
        }

        val fileKey = Crypto.randomBytes(16)
        val header = buildHeader(fileKey, decoded.second)
        val payloadNonce = Crypto.randomBytes(16)
        val payloadKey = Crypto.hkdf(fileKey, payloadNonce, PAYLOAD_INFO)

        return header + payloadNonce + streamSeal(payloadKey, plaintext)
    }

    fun decrypt(identity: Identity, envelope: ByteArray): AgeResult {
        val header = parseHeader(envelope) ?: return AgeResult.Invalid
        if (header.stanzas.none { it.args.firstOrNull() == "X25519" }) {
            return AgeResult.Invalid
        }

        val ourPublicKey = Crypto.x25519PublicKey(identity.seed)
        var fileKey: ByteArray? = null

        for (stanza in header.stanzas) {
            if (stanza.args.size != 2 || stanza.args[0] != "X25519") continue
            if (stanza.body.size != 32) continue

            val ephemeralShare = Crypto.base64Decode(stanza.args[1]) ?: continue
            if (ephemeralShare.size != 32) continue

            val sharedSecret = Crypto.x25519Agreement(identity.seed, ephemeralShare) ?: continue
            val wrapKey = Crypto.hkdf(
                inputKeyMaterial = sharedSecret,
                salt = ephemeralShare + ourPublicKey,
                info = X25519_INFO,
            )
            val candidate = Crypto.aeadOpen(wrapKey, Crypto.zeroNonce, stanza.body) ?: continue
            if (candidate.size != 16) continue

            val macKey = Crypto.hkdf(candidate, info = HEADER_INFO)
            val expectedMac = Crypto.hmacSha256(macKey, header.macInput)
            if (!MessageDigest.isEqual(expectedMac, header.mac)) continue

            fileKey = candidate
            break
        }

        val key = fileKey ?: return AgeResult.NotForMe
        val plaintext = streamOpen(key, envelope, header.payloadOffset) ?: return AgeResult.Invalid
        return AgeResult.ForMe(plaintext)
    }

    private fun buildHeader(fileKey: ByteArray, recipientKey: ByteArray): ByteArray {
        val ephemeralSecret = Crypto.randomBytes(32)
        val ephemeralShare = Crypto.x25519PublicKey(ephemeralSecret)
        val sharedSecret = Crypto.x25519Agreement(ephemeralSecret, recipientKey)
            ?: throw IllegalStateException("invalid recipient key")

        val wrapKey = Crypto.hkdf(
            inputKeyMaterial = sharedSecret,
            salt = ephemeralShare + recipientKey,
            info = X25519_INFO,
        )
        val wrappedFileKey = Crypto.aeadSeal(wrapKey, Crypto.zeroNonce, fileKey)

        val prefix = buildString {
            append(VERSION).append('\n')
            append("-> X25519 ").append(Crypto.base64Encode(ephemeralShare)).append('\n')
            append(wrapBase64(wrappedFileKey))
        }

        val macKey = Crypto.hkdf(fileKey, info = HEADER_INFO)
        val mac = Crypto.hmacSha256(macKey, (prefix + "---").toByteArray(Charsets.US_ASCII))

        return (prefix + "--- " + Crypto.base64Encode(mac) + "\n")
            .toByteArray(Charsets.US_ASCII)
    }

    private fun parseHeader(data: ByteArray): ParsedHeader? {
        var position = 0

        fun readLine(): Pair<String, Int>? {
            val start = position
            var end = start
            while (end < data.size && data[end] != '\n'.code.toByte()) end++
            if (end >= data.size) return null
            position = end + 1
            return String(data, start, end - start, Charsets.US_ASCII) to start
        }

        val versionLine = readLine() ?: return null
        if (versionLine.first != VERSION) return null

        val stanzas = ArrayList<Stanza>()

        while (true) {
            val line = readLine() ?: return null

            if (line.first.startsWith("--- ")) {
                val mac = Crypto.base64Decode(line.first.substring(4)) ?: return null
                if (mac.size != 32) return null
                val macInput = data.copyOfRange(0, line.second + 3)
                if (stanzas.isEmpty()) return null
                return ParsedHeader(stanzas, mac, macInput, position)
            }

            if (!line.first.startsWith("-> ")) return null

            val args = line.first.substring(3).split(' ').filter(String::isNotEmpty)
            if (args.isEmpty()) return null

            val bodyText = StringBuilder()
            while (true) {
                val bodyLine = readLine() ?: return null
                if (bodyLine.first.length > 64) return null
                bodyText.append(bodyLine.first)
                if (bodyLine.first.length < 64) break
            }

            val body = Crypto.base64Decode(bodyText.toString()) ?: return null
            stanzas.add(Stanza(args, body))
        }
    }

    private fun wrapBase64(data: ByteArray): String {
        val encoded = Crypto.base64Encode(data)
        if (encoded.isEmpty()) return "\n"

        val output = StringBuilder()
        var index = 0
        while (index < encoded.length) {
            val end = minOf(index + 64, encoded.length)
            output.append(encoded, index, end).append('\n')
            index = end
        }
        if (encoded.length % 64 == 0) output.append('\n')
        return output.toString()
    }

    private fun streamSeal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var counter = 0L

        if (plaintext.isEmpty()) {
            output.write(Crypto.aeadSeal(key, chunkNonce(counter, finalChunk = true), plaintext))
            return output.toByteArray()
        }

        var offset = 0
        while (offset < plaintext.size) {
            val length = minOf(CHUNK_SIZE, plaintext.size - offset)
            val isFinal = offset + length == plaintext.size
            val chunk = plaintext.copyOfRange(offset, offset + length)
            output.write(Crypto.aeadSeal(key, chunkNonce(counter, isFinal), chunk))
            offset += length
            counter++
        }

        return output.toByteArray()
    }

    private fun streamOpen(fileKey: ByteArray, data: ByteArray, payloadOffset: Int): ByteArray? {
        if (payloadOffset < 0 || data.size - payloadOffset < 16 + TAG_SIZE) return null

        val nonce = data.copyOfRange(payloadOffset, payloadOffset + 16)
        val payloadKey = Crypto.hkdf(fileKey, nonce, PAYLOAD_INFO)

        var position = payloadOffset + 16
        var counter = 0L
        val output = ByteArrayOutputStream()

        while (position < data.size) {
            val remaining = data.size - position
            val isFinal = remaining <= CHUNK_SIZE + TAG_SIZE
            val length = if (isFinal) remaining else CHUNK_SIZE + TAG_SIZE
            if (length < TAG_SIZE) return null

            val plaintext = Crypto.aeadOpen(
                payloadKey,
                chunkNonce(counter, isFinal),
                data.copyOfRange(position, position + length),
            ) ?: return null

            output.write(plaintext)
            position += length
            counter++

            if (isFinal) {
                return if (position == data.size) output.toByteArray() else null
            }
        }

        return null
    }

    private fun chunkNonce(counter: Long, finalChunk: Boolean): ByteArray {
        val nonce = ByteArray(12)
        var value = counter
        for (index in 10 downTo 0) {
            nonce[index] = (value and 0xff).toByte()
            value = value ushr 8
        }
        nonce[11] = if (finalChunk) 1 else 0
        return nonce
    }

    private class Stanza(val args: List<String>, val body: ByteArray)

    private class ParsedHeader(
        val stanzas: List<Stanza>,
        val mac: ByteArray,
        val macInput: ByteArray,
        val payloadOffset: Int,
    )
}
