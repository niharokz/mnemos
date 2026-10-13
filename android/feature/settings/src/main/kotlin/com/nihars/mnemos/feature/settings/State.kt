package com.nihars.mnemos.feature.settings

import androidx.compose.runtime.Immutable

@Immutable
data class SettingsUiState(
    val vaultLabel: String = "",
    val fastAccess: Boolean = true,
    val isDemo: Boolean = false,
    val versionName: String = "",
    val message: String? = null,
    val busy: Boolean = false,
    val update: UpdateCandidate? = null,
    val pinProblem: String? = null,
)
