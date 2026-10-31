package com.nihars.mnemos.core.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nihars.mnemos.core.data.remote.RemoteVaultFs
import com.nihars.mnemos.core.data.remote.ServerAccount
import com.nihars.mnemos.core.data.remote.ServerClient
import com.nihars.mnemos.core.index.IndexDb
import com.nihars.mnemos.core.security.LockManager
import com.nihars.mnemos.core.security.LockStore
import com.nihars.mnemos.core.vaultfs.NioVaultFs
import com.nihars.mnemos.core.vaultfs.VaultFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

/**
 * Where the owner's vault is: a real folder (fast), a SAF tree (slower mode), or a Mnemos
 * server (Settings → Storage; the phone keeps a cache and queues edits made offline).
 */
data class VaultLocation(val path: String? = null, val safTree: String? = null, val server: ServerAccount? = null) {
    val isSet: Boolean get() = path != null || safTree != null || server != null
}

/**
 * The one dependency container (manual wiring, no DI framework). Built once in Application;
 * screens reach it through [LocalAppGraph].
 */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun store(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { app.preferencesDataStoreFile(name) }

    private val boot = store("boot")
    val lockStore = LockStore(store("security"))
    val lock = LockManager(lockStore, scope)
    val ownerSettings = SettingsStore(store("owner"))
    val demoSettings = SettingsStore(store("demo"))
    val demo = DemoVault(app)

    private val _session = MutableStateFlow<VaultSession?>(null)

    /** The signed-in user, or null on the sign-in screen. */
    val session: StateFlow<VaultSession?> = _session.asStateFlow()

    /** Set by :app so features can ask for a widget refresh without depending on :app. */
    var onVaultChanged: () -> Unit = {}

    /** Owner file moved or renamed by the app (old path, new path); widgets re-point to it. */
    var onOwnerMoved: (String, String) -> Unit = { _, _ -> }

    val vaultLocation: Flow<VaultLocation> = boot.data.map { p ->
        val server = p[SERVER_URL]?.let { url -> ServerAccount(url, p[SERVER_USER].orEmpty(), p[SERVER_COOKIE].orEmpty()) }
        VaultLocation(p[VAULT_PATH], p[SAF_TREE], server)
    }

    /** The owner's name, asked once at setup so the app can greet them. */
    val ownerName: Flow<String> = boot.data.map { it[OWNER_NAME].orEmpty() }

    suspend fun setOwnerName(name: String) = boot.edit { it[OWNER_NAME] = name.trim().take(40) }

    suspend fun setVaultPath(path: String) {
        leaveServer()
        boot.edit {
            it[VAULT_PATH] = path
            it.remove(SAF_TREE)
        }
    }

    suspend fun setSafTree(uri: Uri) {
        leaveServer()
        boot.edit {
            it[SAF_TREE] = uri.toString()
            it.remove(VAULT_PATH)
        }
    }

    /**
     * Signs in to a Mnemos server and makes it the owner's storage. [url] may omit https://;
     * only https is accepted (passwords and notes never travel in clear text).
     */
    suspend fun useServer(url: String, user: String, password: String): Result<ServerAccount> = withContext(Dispatchers.IO) {
        val clean = url.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
        if (!clean.startsWith("https://")) return@withContext Result.failure(IllegalArgumentException("Use the server's https:// address."))
        runCatching { ServerClient(clean).login(user.trim(), password) }
            .mapCatching { acc ->
                if (acc.readOnly) error("That is the read-only demo account. Sign in with your own account.")
                boot.edit {
                    it[SERVER_URL] = acc.url
                    it[SERVER_USER] = acc.user
                    it[SERVER_COOKIE] = acc.cookie
                    it.remove(VAULT_PATH)
                    it.remove(SAF_TREE)
                }
                remote = null
                acc
            }
    }

    /** Signs out of the server and forgets its cache (pending edits are lost, so the UI asks first). */
    private suspend fun leaveServer() {
        val loc = vaultLocation.first()
        if (loc.server != null) {
            runCatching { withContext(Dispatchers.IO) { ServerClient(loc.server.url, loc.server.cookie).logout() } }
            remoteFor(loc.server).clearCache()
            remote = null
            boot.edit {
                it.remove(SERVER_URL)
                it.remove(SERVER_USER)
                it.remove(SERVER_COOKIE)
            }
        }
    }

    // One cache/queue per server account, shared by the session, widgets and quick capture.
    @Volatile
    private var remote: RemoteVaultFs? = null

    @Synchronized
    fun remoteFor(acc: ServerAccount): RemoteVaultFs {
        remote?.let { if (it.rootLabel == acc.host) return it }
        val home = File(app.filesDir, "server/" + acc.host.replace(Regex("[^A-Za-z0-9.-]"), "_"))
        return RemoteVaultFs(ServerClient(acc.url, acc.cookie), home, acc.host).also { remote = it }
    }

    /** True when All files access is granted (the fast path). */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** The owner's vault without opening a full session (used by quick capture and widgets). */
    suspend fun ownerFs(): Result<VaultFs> {
        val loc = vaultLocation.first()
        return when {
            loc.server != null -> Result.success(remoteFor(loc.server))
            loc.path != null -> {
                if (!hasAllFilesAccess()) return Result.failure(IllegalStateException("Mnemos needs All files access to open “${loc.path}”."))
                val dir = File(loc.path)
                if (!dir.isDirectory) return Result.failure(IllegalStateException("The vault folder “${loc.path}” is missing. Choose it again in Settings → Vault."))
                Result.success(NioVaultFs(dir.toPath()))
            }
            loc.safTree != null -> {
                val uri = loc.safTree.toUri()
                val granted = app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
                if (!granted) return Result.failure(IllegalStateException("Access to the vault folder was removed. Choose it again in Settings → Vault."))
                Result.success(SafVaultFs(app.contentResolver, uri))
            }
            else -> Result.failure(IllegalStateException("No vault folder chosen yet."))
        }
    }

    /** Opens the owner's vault as the signed-in session. */
    suspend fun openOwner(): Result<VaultSession> = ownerFs().map { fs -> open(UserKind.OWNER, fs, ownerSettings) }

    /** Copies the demo vault on first use, so call it from a coroutine. */
    suspend fun openDemo(): VaultSession = withContext(Dispatchers.IO) { open(UserKind.DEMO, NioVaultFs(demo.ensure().toPath()), demoSettings) }

    /** Back to the sign-in screen. */
    fun signOut() {
        _session.value?.close()
        _session.value = null
    }

    /** Remembers a SAF tree permission across restarts. */
    fun persistTree(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @OptIn(FlowPreview::class)
    private fun open(kind: UserKind, fs: VaultFs, settings: SettingsStore): VaultSession {
        _session.value?.close()
        val s = VaultSession(kind, fs, settings, IndexDb(app, "index-${kind.key}.db"), app.filesDir)
        _session.value = s
        s.rescan()
        // Widgets only show the owner's notes; refresh them (debounced) when the vault changes.
        if (kind == UserKind.OWNER) {
            s.onMoved = { from, to -> onOwnerMoved(from, to) }
            s.scope.launch { s.changes.debounce(1000).collect { onVaultChanged() } }
        }
        // Server mode: send queued edits and pick up changes made elsewhere, every 30 s.
        if (fs is RemoteVaultFs) {
            s.scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val changed = runCatching { fs.sync() }.getOrDefault(emptySet())
                    if (changed.isNotEmpty()) s.externalChange(changed)
                    delay(SYNC_EVERY_MS)
                }
            }
        }
        return s
    }

    private companion object {
        val VAULT_PATH = stringPreferencesKey("vault_path")
        val SAF_TREE = stringPreferencesKey("saf_tree")
        val OWNER_NAME = stringPreferencesKey("owner_name")
        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_USER = stringPreferencesKey("server_user")
        val SERVER_COOKIE = stringPreferencesKey("server_cookie")
        const val SYNC_EVERY_MS = 30_000L
    }
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

/** A ViewModel built from the graph, without a DI framework. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalAppGraph.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}
