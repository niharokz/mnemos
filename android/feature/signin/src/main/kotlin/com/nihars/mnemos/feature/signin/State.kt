package com.nihars.mnemos.feature.signin

import androidx.compose.runtime.Immutable

enum class SignInPhase { CHOOSE, PIN }

@Immutable
data class SignInState(
    val phase: SignInPhase = SignInPhase.CHOOSE,
    val ownerSetUp: Boolean = false,
    val entered: Int = 0,
    val usePassword: Boolean = false,
    val error: String? = null,
    // Bumped on every wrong PIN to replay the shake.
    val shakeKey: Int = 0,
    val cooldownMs: Long = 0,
    val busy: Boolean = false,
    val biometricOn: Boolean = false,
    // Set when the PIN was right but the vault couldn't be opened (permission, missing folder).
    val vaultProblem: String? = null,
    val success: Boolean = false,
    // Once a vault is chosen the demo is gone and "My notes" is the only way in.
    val vaultSet: Boolean = false,
    val name: String = "",
)

enum class SetupStep { NAME, REASON, FOLDER, PIN, BIOMETRIC }

data class FolderRow(val name: String, val path: String, val isVault: Boolean)

@Immutable
data class SetupState(
    val step: SetupStep = SetupStep.NAME,
    val hasAccess: Boolean = false,
    val folder: String = "",
    val folders: List<FolderRow> = emptyList(),
    val folderIsVault: Boolean = false,
    val pinProblem: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
)
