package com.nihars.mnemos.core.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Result of a fingerprint attempt, already checked against the Keystore key. */
sealed interface BiometricOutcome {
    data object Success : BiometricOutcome
    data object Cancelled : BiometricOutcome

    /** A new fingerprint was enrolled; the key is gone and the PIN must be used. */
    data object Invalidated : BiometricOutcome
    data class Error(val message: String) : BiometricOutcome
}

/** BiometricPrompt (BIOMETRIC_STRONG) bound to the Keystore key from [BiometricKeys]. */
object Biometric {
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Creates the key, then asks once so the user confirms it works. */
    fun enroll(activity: FragmentActivity, onResult: (BiometricOutcome) -> Unit) {
        runCatching { BiometricKeys.create() }.onFailure {
            onResult(BiometricOutcome.Error("This phone couldn't create a fingerprint key."))
            return
        }
        prompt(activity, "Turn on fingerprint unlock", onResult)
    }

    fun prompt(activity: FragmentActivity, title: String, onResult: (BiometricOutcome) -> Unit) {
        val cipher = when (val r = BiometricKeys.cipher()) {
            is BiometricKeys.CipherResult.Ready -> r.cipher
            BiometricKeys.CipherResult.Invalidated -> return onResult(BiometricOutcome.Invalidated)
            BiometricKeys.CipherResult.Missing -> return onResult(BiometricOutcome.Invalidated)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val ok = BiometricKeys.confirm(result.cryptoObject?.cipher)
                onResult(if (ok) BiometricOutcome.Success else BiometricOutcome.Error("Fingerprint check failed. Use your PIN."))
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                val cancelled = code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_USER_CANCELED ||
                    code == BiometricPrompt.ERROR_CANCELED
                onResult(if (cancelled) BiometricOutcome.Cancelled else BiometricOutcome.Error(message.toString()))
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Mnemos")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}

/** Walks ContextWrappers up to the hosting FragmentActivity (needed by BiometricPrompt). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
