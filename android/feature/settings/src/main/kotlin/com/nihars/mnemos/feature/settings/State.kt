package com.nihars.mnemos.feature.settings

import androidx.compose.runtime.Immutable
import com.nihars.mnemos.core.data.CleanupReport

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
    /** Cleanup preview waiting for confirmation (dryRun) or the last run's result. */
    val cleanup: CleanupReport? = null,
)
