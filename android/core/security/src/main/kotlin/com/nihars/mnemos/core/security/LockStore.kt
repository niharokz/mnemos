package com.nihars.mnemos.core.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Owner security settings, stored app-private. Holds the PIN hash, never the PIN. */
data class SecuritySettings(
    val pin: PinRecord? = null,
    val biometric: Boolean = false,
    val autoLock: AutoLockDelay = AutoLockDelay.DEFAULT,
    val lockOnScreenOff: Boolean = false,
    val hideInRecents: Boolean = true,
    // Widgets show titles only (no note content) while the app is locked.
    val widgetPrivacy: Boolean = true,
    val captureWhileLocked: Boolean = true,
    val attempts: Attempts = Attempts(),
)

class LockStore(private val store: DataStore<Preferences>) {
    val settings: Flow<SecuritySettings> = store.data.map { p ->
        val salt = p[SALT]
        val hash = p[HASH]
        SecuritySettings(
            pin = if (salt != null && hash != null) PinRecord(salt, p[ITER] ?: PinHasher.MIN_ITERATIONS, hash) else null,
            biometric = p[BIOMETRIC] ?: false,
            autoLock = p[AUTO_LOCK]?.let { name -> AutoLockDelay.entries.firstOrNull { it.name == name } } ?: AutoLockDelay.DEFAULT,
            lockOnScreenOff = p[SCREEN_OFF] ?: false,
            hideInRecents = p[HIDE_RECENTS] ?: true,
            widgetPrivacy = p[WIDGET_PRIVACY] ?: true,
            captureWhileLocked = p[CAPTURE_LOCKED] ?: true,
            attempts = Attempts(p[FAILURES] ?: 0, p[LOCKED_UNTIL] ?: 0L),
        )
    }

    suspend fun current(): SecuritySettings = settings.first()

    suspend fun setPin(record: PinRecord) = store.edit {
        it[SALT] = record.salt
        it[HASH] = record.hash
        it[ITER] = record.iterations
        it[FAILURES] = 0
        it[LOCKED_UNTIL] = 0L
    }

    suspend fun setAttempts(a: Attempts) = store.edit {
        it[FAILURES] = a.failures
        it[LOCKED_UNTIL] = a.lockedUntil
    }

    suspend fun update(transform: (SecuritySettings) -> SecuritySettings) {
        val next = transform(current())
        store.edit {
            it[BIOMETRIC] = next.biometric
            it[AUTO_LOCK] = next.autoLock.name
            it[SCREEN_OFF] = next.lockOnScreenOff
            it[HIDE_RECENTS] = next.hideInRecents
            it[WIDGET_PRIVACY] = next.widgetPrivacy
            it[CAPTURE_LOCKED] = next.captureWhileLocked
        }
    }

    private companion object {
        val SALT = stringPreferencesKey("pin_salt")
        val HASH = stringPreferencesKey("pin_hash")
        val ITER = intPreferencesKey("pin_iterations")
        val BIOMETRIC = booleanPreferencesKey("biometric")
        val AUTO_LOCK = stringPreferencesKey("auto_lock")
        val SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        val HIDE_RECENTS = booleanPreferencesKey("hide_in_recents")
        val WIDGET_PRIVACY = booleanPreferencesKey("widget_privacy")
        val CAPTURE_LOCKED = booleanPreferencesKey("capture_while_locked")
        val FAILURES = intPreferencesKey("pin_failures")
        val LOCKED_UNTIL = longPreferencesKey("pin_locked_until")
    }
}
