package dev.butan.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class AgeCliCompatTest {
    private val random = SecureRandom()

    @Before
    fun requireAgeCli() {
        assumeTrue("reference age CLI is not installed", ageCliAvailable())
    }

    @Test
    fun `kotlin ciphertext decrypts with age cli`() {
        val identity = Identity.generate()
        val identityFile = identityFile(identity)
        val sizes = listOf(0, 11, 64 * 1024, 64 * 1024 + 17, 128 * 1024)

        for (size in sizes) {
            val plaintext = ByteArray(size).also(random::nextBytes)
            val ciphertext = Age.encrypt(identity.ageRecipient, plaintext)
            val decrypted = runAge(
                listOf("age", "--decrypt", "--identity", identityFile.absolutePath),
                ciphertext,
            )
            assertArrayEquals("size $size", plaintext, decrypted)
        }
    }

    @Test
    fun `age cli ciphertext decrypts with kotlin`() {
        val identity = Identity.generate()
        val sizes = listOf(0, 11, 64 * 1024, 64 * 1024 + 17, 128 * 1024)

        for (size in sizes) {
            val plaintext = ByteArray(size).also(random::nextBytes)
            val ciphertext = runAge(
                listOf("age", "--encrypt", "--recipient", identity.ageRecipient),
                plaintext,
            )
            val result = Age.decrypt(identity, ciphertext)
            assertTrue("size $size", result is AgeResult.ForMe)
            assertArrayEquals("size $size", plaintext, (result as AgeResult.ForMe).plaintext)
        }
    }

    private fun identityFile(identity: Identity): File =
        File.createTempFile("butan-identity", ".txt").apply {
            writeText("# butan test identity\n${identity.ageSecret}\n")
            deleteOnExit()
        }

    private fun runAge(command: List<String>, input: ByteArray): ByteArray {
        val inputFile = File.createTempFile("butan-stdin", ".bin").apply { writeBytes(input) }
        val outputFile = File.createTempFile("butan-stdout", ".bin")
        val errorFile = File.createTempFile("butan-stderr", ".txt")
        try {
            val process = ProcessBuilder(command)
                .redirectInput(inputFile)
                .redirectOutput(outputFile)
                .redirectError(errorFile)
                .start()
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "${command.joinToString(" ")} failed ($exitCode): ${errorFile.readText()}"
            }
            return outputFile.readBytes()
        } finally {
            inputFile.delete()
            outputFile.delete()
            errorFile.delete()
        }
    }

    private fun ageCliAvailable(): Boolean = try {
        val process = ProcessBuilder("age", "--version")
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
