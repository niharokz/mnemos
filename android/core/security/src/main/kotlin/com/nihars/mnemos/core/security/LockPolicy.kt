package com.nihars.mnemos.core.security

/**
 * Wrong-PIN state. Stored by the app so the cooldown survives restarts.
 * From the 5th wrong PIN on, each failure starts a cooldown: 30 s, 60 s, 120 s… capped at 15 min.
 */
data class Attempts(val failures: Int = 0, val lockedUntil: Long = 0L) {
    fun cooldownRemaining(now: Long): Long = maxOf(0L, lockedUntil - now)
    fun isCoolingDown(now: Long): Boolean = cooldownRemaining(now) > 0

    fun onFailure(now: Long): Attempts {
        val n = failures + 1
        if (n < FREE_ATTEMPTS) return Attempts(n, 0L)
        val doublings = (n - FREE_ATTEMPTS).coerceAtMost(10)
        val cooldown = minOf(MAX_COOLDOWN_MS, BASE_COOLDOWN_MS shl doublings)
        return Attempts(n, now + cooldown)
    }

    fun onSuccess(): Attempts = Attempts()

    companion object {
        const val FREE_ATTEMPTS = 5
        const val BASE_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 15 * 60_000L
    }
}

/** Auto-lock delays offered in Settings. */
enum class AutoLockDelay(val millis: Long, val label: String) {
    IMMEDIATELY(0, "Immediately"),
    SECONDS_30(30_000, "30 seconds"),
    MINUTE_1(60_000, "1 minute"),
    MINUTES_5(300_000, "5 minutes"),
    MINUTES_15(900_000, "15 minutes"),
    ;

    /** True when the app was in the background long enough that it must lock on return. */
    fun shouldLock(backgroundedAt: Long, now: Long): Boolean = now - backgroundedAt >= millis

    companion object {
        val DEFAULT = MINUTES_5
    }
}

/** PIN/password rules for the setup screen. */
object PinRules {
    const val MIN = 4
    const val MAX = 32

    /** Returns a user-facing problem, or null if [pin] is acceptable. */
    fun problem(pin: String, confirm: String): String? = when {
        pin.length < MIN -> "Use at least $MIN characters."
        pin.length > MAX -> "Use at most $MAX characters."
        pin != confirm -> "The two entries don't match."
        else -> null
    }
}
