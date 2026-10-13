package com.nihars.mnemos.feature.signin

import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nihars.mnemos.core.security.BiometricOutcome
import com.nihars.mnemos.core.data.AppGraph
import com.nihars.mnemos.core.security.PinResult
import com.nihars.mnemos.core.security.PinRules
import com.nihars.mnemos.core.text.NaturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sign-in and re-lock. [relock] means a session is already open and only the lock must be passed. */
class SignInViewModel(private val graph: AppGraph, private val relock: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(SignInState(phase = if (relock) SignInPhase.PIN else SignInPhase.CHOOSE))
    val state: StateFlow<SignInState> = _state.asStateFlow()

    // The PIN lives here, never in UI state, and is wiped after each check.
    private val pin = StringBuilder()
    private var cooldownJob: Job? = null

    init {
        viewModelScope.launch {
            graph.lockStore.settings.collect { s ->
                _state.update { it.copy(ownerSetUp = s.pin != null, biometricOn = s.biometric) }
                skipChooserIfSetUp()
            }
        }
        viewModelScope.launch {
            graph.vaultLocation.collect { loc ->
                _state.update { it.copy(vaultSet = loc.isSet) }
                skipChooserIfSetUp()
            }
        }
        viewModelScope.launch { graph.ownerName.collect { n -> _state.update { it.copy(name = n) } } }
        viewModelScope.launch {
            val a = graph.lockStore.current().attempts
            val left = a.cooldownRemaining(System.currentTimeMillis())
            if (left > 0) startCooldown(left)
        }
    }

    // With a vault and PIN in place there is nothing to choose: go straight to the PIN pad.
    private fun skipChooserIfSetUp() {
        val s = _state.value
        if (s.vaultSet && s.ownerSetUp && s.phase == SignInPhase.CHOOSE) _state.update { it.copy(phase = SignInPhase.PIN) }
    }

    /** Returns false when the owner hasn't set up yet (the screen then opens the setup wizard). */
    fun chooseOwner(): Boolean {
        if (!_state.value.ownerSetUp) return false
        _state.update { it.copy(phase = SignInPhase.PIN, error = null) }
        return true
    }

    fun back() {
        if (relock || _state.value.vaultSet) return
        clearPin()
        _state.update { it.copy(phase = SignInPhase.CHOOSE, error = null) }
    }

    fun digit(c: Char) {
        if (busyOrCooling() || pin.length >= PinRules.MAX) return
        pin.append(c)
        _state.update { it.copy(entered = pin.length, error = null) }
    }

    fun backspace() {
        if (pin.isEmpty()) return
        pin.setLength(pin.length - 1)
        _state.update { it.copy(entered = pin.length) }
    }

    fun setPassword(text: String) {
        pin.setLength(0)
        pin.append(text.take(PinRules.MAX))
        _state.update { it.copy(entered = pin.length, error = null) }
    }

    fun togglePassword() {
        clearPin()
        _state.update { it.copy(usePassword = !it.usePassword) }
    }

    fun submit() {
        if (busyOrCooling() || pin.length < PinRules.MIN) return
        val chars = CharArray(pin.length) { pin[it] }
        clearPin()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = graph.lock.checkPin(chars)) {
                PinResult.Ok -> afterUnlock()
                is PinResult.Wrong -> _state.update {
                    it.copy(busy = false, error = "Wrong PIN. ${r.attemptsLeftBeforeCooldown} more before a pause.", shakeKey = it.shakeKey + 1)
                }
                is PinResult.CoolingDown -> {
                    _state.update { it.copy(busy = false, shakeKey = it.shakeKey + 1) }
                    startCooldown(r.millis)
                }
            }
        }
    }

    fun biometric(outcome: BiometricOutcome) {
        when (outcome) {
            BiometricOutcome.Success -> viewModelScope.launch {
                graph.lock.unlockWithBiometric()
                afterUnlock()
            }
            BiometricOutcome.Cancelled -> Unit
            BiometricOutcome.Invalidated -> viewModelScope.launch {
                graph.lockStore.update { it.copy(biometric = false) }
                _state.update {
                    it.copy(error = "Fingerprints changed on this phone. Enter your PIN, then turn fingerprint unlock on again in Settings.")
                }
            }
            is BiometricOutcome.Error -> _state.update { it.copy(error = outcome.message) }
        }
    }

    fun chooseDemo() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            graph.openDemo()
            _state.update { it.copy(busy = false, success = true) }
        }
    }

    private suspend fun afterUnlock() {
        if (relock && graph.session.value != null) {
            _state.update { it.copy(busy = false, success = true) }
            return
        }
        val opened = graph.openOwner()
        _state.update {
            if (opened.isSuccess) it.copy(busy = false, success = true) else it.copy(busy = false, vaultProblem = opened.exceptionOrNull()?.message)
        }
    }

    private fun startCooldown(ms: Long) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            var left = ms
            while (left > 0) {
                _state.update { it.copy(cooldownMs = left, error = "Too many tries. Wait ${formatMs(left)}.") }
                delay(minOf(1000L, left))
                left -= 1000L
            }
            _state.update { it.copy(cooldownMs = 0, error = null) }
        }
    }

    private fun busyOrCooling() = _state.value.busy || _state.value.cooldownMs > 0

    private fun clearPin() {
        for (i in pin.indices) pin.setCharAt(i, ' ')
        pin.setLength(0)
        _state.update { it.copy(entered = 0) }
    }

    override fun onCleared() = clearPin()

    companion object {
        fun formatMs(ms: Long): String {
            val s = (ms + 999) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/** First-run wizard (or just the vault step when [vaultOnly], from Settings → Vault). */
class SetupViewModel(private val graph: AppGraph, private val vaultOnly: Boolean) : ViewModel() {
    private val _state = MutableStateFlow(SetupState(step = if (vaultOnly) SetupStep.REASON else SetupStep.NAME))
    val state: StateFlow<SetupState> = _state.asStateFlow()

    init {
        refreshAccess()
    }

    fun setName(name: String) = viewModelScope.launch {
        graph.setOwnerName(name)
        _state.update { it.copy(step = SetupStep.REASON) }
        refreshAccess()
    }

    /** Called on resume, after the user returns from the All files access settings page. */
    fun refreshAccess() {
        val access = graph.hasAllFilesAccess()
        _state.update { it.copy(hasAccess = access) }
        if (access && _state.value.step == SetupStep.REASON) {
            _state.update { it.copy(step = SetupStep.FOLDER) }
            openFolder(Environment.getExternalStorageDirectory().absolutePath)
        }
    }

    fun openFolder(path: String) {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                File(path).listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.startsWith('.') }
                    .sortedWith { a, b -> NaturalCompare.compare(a.name, b.name) }
                    .map { FolderRow(it.name, it.absolutePath, isVault(it)) }
            }
            val here = withContext(Dispatchers.IO) { isVault(File(path)) }
            _state.update { it.copy(folder = path, folders = rows, folderIsVault = here) }
        }
    }

    fun up() {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val parent = File(_state.value.folder).parentFile ?: return
        if (_state.value.folder != root && parent.absolutePath.startsWith(root)) openFolder(parent.absolutePath)
    }

    fun useFolder() {
        viewModelScope.launch {
            graph.setVaultPath(_state.value.folder)
            afterVault()
        }
    }

    fun useSafTree(uri: Uri) {
        viewModelScope.launch {
            runCatching { graph.persistTree(uri) }
            graph.setSafTree(uri)
            afterVault()
        }
    }

    fun setPin(pin: String, confirm: String, biometricAvailable: Boolean) {
        val problem = PinRules.problem(pin, confirm)
        if (problem != null) {
            _state.update { it.copy(pinProblem = problem) }
            return
        }
        _state.update { it.copy(busy = true, pinProblem = null) }
        viewModelScope.launch {
            graph.lock.setPin(pin.toCharArray())
            _state.update { it.copy(busy = false) }
            if (biometricAvailable) _state.update { it.copy(step = SetupStep.BIOMETRIC) } else finish()
        }
    }

    fun biometricResult(outcome: BiometricOutcome) {
        viewModelScope.launch {
            if (outcome == BiometricOutcome.Success) graph.lockStore.update { it.copy(biometric = true) }
            finish()
        }
    }

    fun skipBiometric() = viewModelScope.launch { finish() }

    private suspend fun afterVault() {
        val pinSet = graph.lockStore.current().pin != null
        if (vaultOnly || pinSet) finish() else _state.update { it.copy(step = SetupStep.PIN) }
    }

    private suspend fun finish() {
        val opened = graph.openOwner()
        _state.update {
            if (opened.isSuccess) it.copy(done = true) else it.copy(message = opened.exceptionOrNull()?.message)
        }
    }

    private fun isVault(dir: File) = File(dir, ".obsidian").isDirectory || File(dir, ".stfolder").exists()
}
