package com.nihars.mnemos.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {
    @Test fun pinHashVerifies() {
        val rec = PinHasher.create("2468".toCharArray(), PinHasher.MIN_ITERATIONS)
        assertTrue(PinHasher.verify("2468".toCharArray(), rec))
        assertFalse(PinHasher.verify("2469".toCharArray(), rec))
        val other = PinHasher.create("2468".toCharArray(), PinHasher.MIN_ITERATIONS)
        assertTrue(rec.salt != other.salt && rec.hash != other.hash)
    }

    @Test fun calibrationNeverGoesBelowMinimum() {
        var t = 0L
        // A fake clock that claims hashing is very slow.
        val slow = PinHasher.calibrate(250) { t.also { t += 10_000_000_000L } }
        assertEquals(PinHasher.MIN_ITERATIONS, slow)
    }

    @Test fun cooldownStartsAtFifthFailureAndDoubles() {
        var a = Attempts()
        repeat(4) { a = a.onFailure(1_000) }
        assertFalse(a.isCoolingDown(1_000))
        a = a.onFailure(1_000)
        assertEquals(30_000L, a.cooldownRemaining(1_000))
        a = a.onFailure(40_000)
        assertEquals(60_000L, a.cooldownRemaining(40_000))
        repeat(20) { a = a.onFailure(0) }
        assertEquals(Attempts.MAX_COOLDOWN_MS, a.cooldownRemaining(0))
        assertEquals(Attempts(), a.onSuccess())
    }

    @Test fun autoLockAndRules() {
        assertTrue(AutoLockDelay.IMMEDIATELY.shouldLock(10, 10))
        assertFalse(AutoLockDelay.MINUTES_5.shouldLock(0, 299_999))
        assertTrue(AutoLockDelay.MINUTES_5.shouldLock(0, 300_000))
        assertNotNull(PinRules.problem("123", "123"))
        assertNotNull(PinRules.problem("1234", "1235"))
        assertNull(PinRules.problem("1234", "1234"))
    }
}
