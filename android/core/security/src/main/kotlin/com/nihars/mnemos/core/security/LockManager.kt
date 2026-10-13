package com.nihars.mnemos.core.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LockState { UNKNOWN, NOT_SET_UP, LOCKED, UNLOCKED }

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsLeftBeforeCooldown: Int) : PinResult
    data class CoolingDown(val millis: Long) : PinResult
}

/** The owner lock. Starts LOCKED once a PIN exists; the demo user never goes through it. */
class LockManager(
    private val store: LockStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LockState.UNKNOWN)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var backgroundedAt: Long? = null

    init {
        scope.launch { _state.value = if (store.current().pin == null) LockState.NOT_SET_UP else LockState.LOCKED }
    }

    suspend fun setPin(pin: CharArray) {
        // Hashing takes ~250 ms on purpose; keep it off the main thread.
        val record = withContext(Dispatchers.Default) { PinHasher.create(pin, PinHasher.calibrate()) }
        pin.fill(' ')
        store.setPin(record)
        _state.value = LockState.UNLOCKED
    }

    suspend fun checkPin(pin: CharArray): PinResult {
        val s = store.current()
        val now = clock()
        if (s.attempts.isCoolingDown(now)) return PinResult.CoolingDown(s.attempts.cooldownRemaining(now))
        val record = s.pin ?: return PinResult.Ok
        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, record) }
        pin.fill(' ')
        return if (ok) {
            store.setAttempts(s.attempts.onSuccess())
            _state.value = LockState.UNLOCKED
            PinResult.Ok
        } else {
            val next = s.attempts.onFailure(now)
            store.setAttempts(next)
            if (next.isCoolingDown(now)) {
                PinResult.CoolingDown(next.cooldownRemaining(now))
            } else {
                PinResult.Wrong(Attempts.FREE_ATTEMPTS - next.failures)
            }
        }
    }

    /** Called after BiometricPrompt succeeded and [BiometricKeys.confirm] passed. */
    suspend fun unlockWithBiometric() {
        store.setAttempts(Attempts())
        _state.value = LockState.UNLOCKED
    }

    fun lockNow() {
        if (_state.value == LockState.UNLOCKED) _state.value = LockState.LOCKED
    }

    fun onBackground() {
        backgroundedAt = clock()
    }

    /** Locks on return when the app was away longer than the auto-lock delay. */
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        scope.launch {
            if (store.current().autoLock.shouldLock(since, clock())) lockNow()
        }
    }

    fun onScreenOff() {
        scope.launch { if (store.current().lockOnScreenOff) lockNow() }
    }
}
