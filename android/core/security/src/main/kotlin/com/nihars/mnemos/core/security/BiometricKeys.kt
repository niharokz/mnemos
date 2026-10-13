package com.nihars.mnemos.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A Keystore key that only works right after a strong biometric. Enrolling a new fingerprint
 * invalidates it, so someone who adds their finger can't unlock the app.
 */
object BiometricKeys {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "mnemos_unlock"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    sealed interface CipherResult {
        data class Ready(val cipher: Cipher) : CipherResult

        /** The key was invalidated (new fingerprint enrolled) and deleted; ask for the PIN. */
        data object Invalidated : CipherResult
        data object Missing : CipherResult
    }

    fun create() {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        gen.generateKey()
    }

    /** A cipher to hand to BiometricPrompt; it only becomes usable after the user authenticates. */
    fun cipher(): CipherResult {
        val key = (keyStore().getKey(ALIAS, null) as? SecretKey) ?: return CipherResult.Missing
        return try {
            CipherResult.Ready(Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) })
        } catch (_: KeyPermanentlyInvalidatedException) {
            delete()
            CipherResult.Invalidated
        }
    }

    /** Proves the prompt really unlocked the key (guards against a spoofed success callback). */
    fun confirm(cipher: Cipher?): Boolean = runCatching { cipher?.doFinal(byteArrayOf(1)) != null }.getOrDefault(false)

    fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }
}
