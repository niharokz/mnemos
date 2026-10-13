package com.nihars.mnemos.feature.signin

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nihars.mnemos.core.security.Biometric
import com.nihars.mnemos.core.security.findFragmentActivity
import com.nihars.mnemos.core.data.graphViewModel
import com.nihars.mnemos.core.design.GridBackground
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBadge
import com.nihars.mnemos.core.design.NssIcons

/** First-run setup: access → vault folder → PIN → fingerprint. [vaultOnly] stops after the folder. */
@Composable
fun SetupScreen(vaultOnly: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val vm = graphViewModel(key = "setup-$vaultOnly") { SetupViewModel(it, vaultOnly) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val motion = Nss.motion
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::useSafTree) }

    GridBackground {
        Column(Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize().widthIn(max = 560.dp).align(Alignment.TopCenter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(NssIcons.Back, contentDescription = "Cancel setup") }
                Text(
                    when (state.step) {
                        SetupStep.NAME -> "Welcome to Mnemos"
                        SetupStep.REASON -> "Access your notes"
                        SetupStep.FOLDER -> "Choose your vault folder"
                        SetupStep.PIN -> "Create a PIN or password"
                        SetupStep.BIOMETRIC -> "Fingerprint unlock"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
            state.message?.let {
                Text(it, color = Nss.colors.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            AnimatedContent(state.step, transitionSpec = { motion.sharedAxisX(true) }, label = "setup") { step ->
                when (step) {
                    SetupStep.NAME -> NameStep(vm::setName)
                    SetupStep.REASON -> ReasonStep(
                        onGrant = {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                            runCatching { context.startActivity(intent) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        },
                        onSlowMode = { pickTree.launch(null) },
                    )
                    SetupStep.FOLDER -> FolderStep(state, vm::openFolder, vm::up, vm::useFolder)
                    SetupStep.PIN -> PinStep(state.pinProblem, state.busy) { pin, confirm -> vm.setPin(pin, confirm, Biometric.available(context)) }
                    SetupStep.BIOMETRIC -> BiometricStep(
                        onEnable = { context.findFragmentActivity()?.let { a -> Biometric.enroll(a, vm::biometricResult) } },
                        onSkip = { vm.skipBiometric() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NameStep(onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What should Mnemos call you?", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            name,
            { name = it.take(40) },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { if (name.isNotBlank()) onDone(name) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onDone(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun ReasonStep(onGrant: () -> Unit, onSlowMode: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Mnemos reads and saves your notes as plain files in a folder you choose, such as your Syncthing or Obsidian folder. " +
                "To do that quickly it needs All files access. It has no internet permission, so nothing ever leaves the phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Grant All files access") }
        OutlinedButton(onClick = onSlowMode, modifier = Modifier.fillMaxWidth()) { Text("Pick a folder instead (slower mode)") }
        Text(
            "Slower mode uses Android's folder picker. Search indexing is slower and saves overwrite in place instead of an atomic rename.",
            style = MaterialTheme.typography.bodySmall,
            color = Nss.colors.muted,
        )
    }
}

@Composable
private fun FolderStep(state: SetupState, onOpen: (String) -> Unit, onUp: () -> Unit, onUse: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUp) { Icon(NssIcons.ArrowUp, contentDescription = "Parent folder") }
            Text(state.folder, style = MaterialTheme.typography.bodySmall, color = Nss.colors.muted, modifier = Modifier.weight(1f))
            if (state.folderIsVault) NssBadge("vault", Nss.colors.ok)
        }
        LazyColumn(Modifier.height(360.dp)) {
            items(state.folders, key = { it.path }) { row ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(row.path) }.padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NssIcons.Folder, contentDescription = null, tint = if (row.isVault) MaterialTheme.colorScheme.primary else Nss.colors.muted)
                    Spacer(Modifier.width(12.dp))
                    Text(row.name, modifier = Modifier.weight(1f))
                    // Folders holding .obsidian or .stfolder are likely the vault.
                    if (row.isVault) NssBadge("vault", Nss.colors.ok)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onUse, modifier = Modifier.fillMaxWidth(), enabled = state.folder.isNotEmpty()) { Text("Use this folder") }
    }
}

@Composable
private fun PinStep(problem: String?, busy: Boolean, onSet: (String, String) -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var numeric by rememberSaveable { mutableStateOf(true) }
    val type = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("4–32 characters. You'll need it every time Mnemos locks.", style = MaterialTheme.typography.bodyMedium, color = Nss.colors.muted)
        OutlinedTextField(
            pin, { pin = it }, label = { Text(if (numeric) "PIN" else "Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = type),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            confirm, { confirm = it }, label = { Text("Repeat it") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = type),
            isError = problem != null, supportingText = { if (problem != null) Text(problem) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { numeric = !numeric }) { Text(if (numeric) "Use a password instead" else "Use a numeric PIN") }
        Button(onClick = { onSet(pin, confirm) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Securing…" else "Continue")
        }
    }
}

@Composable
private fun BiometricStep(onEnable: () -> Unit, onSkip: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(NssIcons.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
        Text(
            "Unlock with a strong fingerprint. Your PIN always works too. If someone adds a new fingerprint to this phone, " +
                "fingerprint unlock turns itself off until you enter your PIN.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) { Text("Turn on fingerprint unlock") }
        TextButton(onClick = onSkip) { Text("Not now") }
    }
}
