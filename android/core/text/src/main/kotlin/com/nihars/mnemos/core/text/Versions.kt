package com.nihars.mnemos.core.text

import java.security.MessageDigest

/** Content version used for conflict checks: SHA-256 of the bytes, first 12 bytes in hex (same rule as the web app). */
object Versions {
    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(24)
        for (i in 0 until 12) {
            val v = digest[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
