package com.nihars.mnemos.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** What is stored for the PIN: never the PIN itself. */
data class PinRecord(val salt: String, val iterations: Int, val hash: String)

/** PBKDF2-HMAC-SHA256 with a random 16-byte salt and a device-calibrated iteration count. */
object PinHasher {
    const val MIN_ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** Picks iterations so one hash takes about [targetMs] on this device, never fewer than the minimum. */
    fun calibrate(targetMs: Long = 250, clock: () -> Long = System::nanoTime): Int {
        val probe = 20_000
        val salt = ByteArray(16)
        val start = clock()
        derive("calibrate".toCharArray(), salt, probe)
        val elapsedMs = maxOf(1L, (clock() - start) / 1_000_000)
        val scaled = (probe.toLong() * targetMs / elapsedMs).coerceAtMost(5_000_000L).toInt()
        return maxOf(MIN_ITERATIONS, scaled)
    }

    fun create(pin: CharArray, iterations: Int, random: SecureRandom = SecureRandom()): PinRecord {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(pin, salt, iterations)
        return PinRecord(b64(salt), iterations, b64(hash))
    }

    /** Constant-time comparison so timing doesn't leak how much of the PIN matched. */
    fun verify(pin: CharArray, record: PinRecord): Boolean {
        val salt = Base64.getDecoder().decode(record.salt)
        val expected = Base64.getDecoder().decode(record.hash)
        return MessageDigest.isEqual(derive(pin, salt, record.iterations), expected)
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
