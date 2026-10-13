package com.nihars.mnemos.feature.signin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.security.Biometric
import com.nihars.mnemos.core.security.findFragmentActivity
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.BrandMark
import com.nihars.mnemos.core.design.GridBackground
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.design.PinDots
import com.nihars.mnemos.core.design.rememberHaptics

/**
 * Sign-in: choose "My notes" (then PIN or fingerprint) or "Demo user". With [relock] the
 * session stays open and only the PIN pad shows.
 */
@Composable
fun SignInScreen(
    relock: Boolean,
    onUnlocked: () -> Unit,
    onSetup: () -> Unit,
    onVaultProblem: (String) -> Unit,
) {
    val vm = graphViewModel(key = "signin-$relock") { SignInViewModel(it, relock) }
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val motion = Nss.motion

    LaunchedEffect(state.success) {
        if (state.success) {
            haptics.confirm()
            onUnlocked()
        }
    }
    LaunchedEffect(state.vaultProblem) { state.vaultProblem?.let(onVaultProblem) }
    LaunchedEffect(state.shakeKey) { if (state.shakeKey > 0) haptics.reject() }

    fun askFingerprint() {
        val activity = context.findFragmentActivity() ?: return
        if (state.biometricOn && Biometric.available(context)) Biometric.prompt(activity, "Unlock Mnemos", vm::biometric)
    }
    // Offer the fingerprint as soon as the PIN pad appears.
    LaunchedEffect(state.phase, state.biometricOn) { if (state.phase == SignInPhase.PIN && state.cooldownMs == 0L) askFingerprint() }

    GridBackground {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).align(Alignment.Center).widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark()
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.name.isNotEmpty()) "Welcome back, ${state.name}" else "Your notes, offline, in plain files",
                style = MaterialTheme.typography.bodyMedium,
                color = Nss.colors.muted,
            )
            Spacer(Modifier.height(32.dp))
            AnimatedContent(state.phase, transitionSpec = { motion.sharedAxisX(targetState == SignInPhase.PIN) }, label = "signin") { phase ->
                when (phase) {
                    SignInPhase.CHOOSE -> Choices(
                        busy = state.busy,
                        showDemo = !state.vaultSet,
                        onOwner = { if (!vm.chooseOwner()) onSetup() },
                        onDemo = vm::chooseDemo,
                    )
                    SignInPhase.PIN -> PinPad(
                        state = state,
                        onDigit = { haptics.tick(); vm.digit(it) },
                        onBackspace = { haptics.tick(); vm.backspace() },
                        onSubmit = vm::submit,
                        onPassword = vm::setPassword,
                        onTogglePassword = vm::togglePassword,
                        onFingerprint = ::askFingerprint,
                        onBack = if (relock) null else vm::back,
                    )
                }
            }
        }
    }
}

@Composable
private fun Choices(busy: Boolean, showDemo: Boolean, onOwner: () -> Unit, onDemo: () -> Unit) {
    val motion = Nss.motion
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The demo is only for the very first look; it disappears once a vault is chosen.
        listOfNotNull(
            Triple(NssIcons.Lock, "My notes" to "Your vault, behind your PIN", onOwner),
            if (showDemo) Triple(NssIcons.FileText, "Demo user" to "Try it with sample files — nothing touches your notes", onDemo) else null,
        ).forEachIndexed { i, (icon, text, action) ->
            val visible = remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible.value = true }
            AnimatedVisibility(visible.value, enter = motion.riseIn(delay = 120 + i * 60)) {
                ChoiceCard(icon, text.first, text.second, enabled = !busy, onClick = action)
            }
        }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, body: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted)
            }
        }
    }
}

@Composable
private fun PinPad(
    state: SignInState,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onPassword: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onFingerprint: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val motion = Nss.motion
    // Success: dots shrink into the cursor before the reveal.
    val collapse = remember { Animatable(1f) }
    LaunchedEffect(state.success) { if (state.success && motion.full) collapse.animateTo(0f, tween(220)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (state.usePassword) "Enter your password" else "Enter your PIN", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        if (state.usePassword) {
            var text by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onPassword(it)
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onSubmit()
                    text = ""
                }),
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PinDots(
                length = maxOf(4, state.entered),
                filled = state.entered,
                error = state.error != null && state.shakeKey > 0,
                shakeKey = state.shakeKey,
                modifier = Modifier.scale(collapse.value).alpha(collapse.value),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            state.error?.let { Text(it, color = Nss.colors.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (!state.usePassword) {
            Keypad(
                enabled = !state.busy && state.cooldownMs == 0L,
                showFingerprint = state.biometricOn,
                canSubmit = state.entered >= 4,
                onDigit = onDigit,
                onBackspace = onBackspace,
                onSubmit = onSubmit,
                onFingerprint = onFingerprint,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            onBack?.let { TextButton(onClick = it) { Text("Back") } }
            TextButton(onClick = onTogglePassword) { Text(if (state.usePassword) "Use PIN pad" else "Use password") }
        }
    }
}

@Composable
private fun Keypad(
    enabled: Boolean,
    showFingerprint: Boolean,
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onFingerprint: () -> Unit,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { r.forEach { d -> Key(d.toString(), enabled) { onDigit(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showFingerprint) FingerprintKey(enabled, onFingerprint) else IconKey(NssIcons.Backspace, "Delete", enabled, onBackspace)
            Key("0", enabled) { onDigit('0') }
            FilledIconButton(onClick = onSubmit, enabled = enabled && canSubmit, modifier = Modifier.size(72.dp)) {
                Icon(NssIcons.Check, contentDescription = "Unlock")
            }
        }
        if (showFingerprint) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { IconKey(NssIcons.Backspace, "Delete", enabled, onBackspace) }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .size(72.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
private fun IconKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(72.dp)) { Icon(icon, contentDescription = description) }
}

/** Fingerprint key: the icon ripples outward on a loop while it's on screen. */
@Composable
private fun FingerprintKey(enabled: Boolean, onClick: () -> Unit) {
    val motion = Nss.motion
    val ripple = rememberInfiniteTransition(label = "fp")
    val r by ripple.animateFloat(1f, if (motion.full) 1.25f else 1f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "fpScale")
    val a by ripple.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "fpAlpha")
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        if (motion.full) {
            Box(
                Modifier.size(56.dp).scale(r).alpha(a)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        IconButton(onClick = onClick, enabled = enabled) { Icon(NssIcons.Fingerprint, contentDescription = "Unlock with fingerprint") }
    }
}
