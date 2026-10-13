package com.nihars.mnemos.app

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nihars.mnemos.core.data.AppGraph
import com.nihars.mnemos.core.data.FileKind
import com.nihars.mnemos.core.data.FileKinds
import com.nihars.mnemos.core.data.LocalAppGraph
import com.nihars.mnemos.core.data.UserKind
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.design.Appearance
import com.nihars.mnemos.core.design.BrandMark
import com.nihars.mnemos.core.design.CircularReveal
import com.nihars.mnemos.core.design.EmptyState
import com.nihars.mnemos.core.design.GridBackground
import com.nihars.mnemos.core.design.MotionTokens
import com.nihars.mnemos.core.design.Nss
import com.nihars.mnemos.core.design.NssBadge
import com.nihars.mnemos.core.design.NssIcons
import com.nihars.mnemos.core.design.MnemosTheme
import com.nihars.mnemos.core.security.LockState
import com.nihars.mnemos.core.vaultfs.ConflictFiles
import com.nihars.mnemos.feature.conflicts.ConflictScreen
import com.nihars.mnemos.feature.editor.EditorScreen
import com.nihars.mnemos.feature.recent.RecentScreen
import com.nihars.mnemos.feature.search.SearchScreen
import com.nihars.mnemos.feature.settings.SettingsScreen
import com.nihars.mnemos.feature.signin.SetupScreen
import com.nihars.mnemos.feature.signin.SignInScreen
import com.nihars.mnemos.feature.table.TableScreen
import com.nihars.mnemos.feature.tree.FoldersScreen
import com.nihars.mnemos.feature.tree.TrashScreen
import com.nihars.mnemos.feature.tree.TreeScreen
import com.nihars.mnemos.feature.viewer.BinaryScreen
import com.nihars.mnemos.feature.viewer.ImageScreen
import com.nihars.mnemos.feature.viewer.LargeTextScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val label: String, val icon: ImageVector) {
    NOTES("Notes", NssIcons.Folder),
    SEARCH("Search", NssIcons.Search),
    RECENT("Recent", NssIcons.Recent),
    SETTINGS("Settings", NssIcons.Settings),
}

private object Routes {
    const val SIGNIN = "signin"
    const val SETUP = "setup?vaultOnly={vaultOnly}"
    const val MAIN = "main"
    const val FILE = "file?path={path}&q={q}"
    const val TRASH = "trash"
    const val FOLDERS = "folders"

    fun setup(vaultOnly: Boolean) = "setup?vaultOnly=$vaultOnly"
    fun file(path: String, q: String? = null) = "file?path=${Uri.encode(path)}&q=${Uri.encode(q.orEmpty())}"
}

@Composable
fun AppRoot(graph: AppGraph, actions: MutableStateFlow<AppAction?>) {
    CompositionLocalProvider(LocalAppGraph provides graph) {
        val session by graph.session.collectAsStateWithLifecycle()
        // Before sign-in the owner's appearance is used, so the lock screen matches the app.
        val sessionSettings by (session?.settings ?: flowOf(null)).collectAsStateWithLifecycle(initialValue = null)
        val ownerSettings by graph.ownerSettings.settings.collectAsStateWithLifecycle(initialValue = null)
        MnemosTheme(sessionSettings?.appearance ?: ownerSettings?.appearance ?: Appearance()) {
            val lock by graph.lock.state.collectAsStateWithLifecycle()
            val nav = rememberNavController()
            Box(Modifier.fillMaxSize()) {
                AppNavHost(graph, nav, session, actions)
                // Re-lock overlay: the session (and any open editor) stays, hidden behind the PIN pad.
                if (session?.kind == UserKind.OWNER && lock == LockState.LOCKED) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SignInScreen(relock = true, onUnlocked = {}, onSetup = {}, onVaultProblem = {})
                    }
                }
            }
            // Signed out (or session lost): back to the sign-in screen.
            LaunchedEffect(session) {
                val route = nav.currentDestination?.route
                if (session == null && route != null && route != Routes.SIGNIN && !route.startsWith("setup")) {
                    nav.navigate(Routes.SIGNIN) { popUpTo(0) }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(graph: AppGraph, nav: NavHostController, session: VaultSession?, actions: MutableStateFlow<AppAction?>) {
    val motion = Nss.motion
    NavHost(
        nav,
        startDestination = Routes.SIGNIN,
        // Forward: the new page glides in a little and fades up; back: the old page sinks away softly.
        enterTransition = {
            if (motion.full) {
                slideInHorizontally(tween(MotionTokens.LONG, easing = MotionTokens.EmphasizedDecelerate)) { it / 14 } +
                    fadeIn(tween(MotionTokens.MEDIUM, delayMillis = 40, easing = MotionTokens.Standard))
            } else {
                fadeIn(motion.enter())
            }
        },
        exitTransition = {
            if (motion.full) fadeOut(tween(MotionTokens.SHORT)) + scaleOut(tween(MotionTokens.MEDIUM), targetScale = 0.98f) else fadeOut(motion.exit())
        },
        popEnterTransition = {
            if (motion.full) fadeIn(tween(MotionTokens.MEDIUM, easing = MotionTokens.Standard)) + scaleIn(tween(MotionTokens.MEDIUM, easing = MotionTokens.EmphasizedDecelerate), initialScale = 0.98f) else fadeIn(motion.enter())
        },
        // Predictive back follows the finger: the page shrinks slightly and slides as it fades.
        popExitTransition = {
            if (motion.full) {
                scaleOut(tween(MotionTokens.MEDIUM), targetScale = 0.94f) +
                    slideOutHorizontally(tween(MotionTokens.MEDIUM, easing = MotionTokens.EmphasizedAccelerate)) { it / 14 } +
                    fadeOut(tween(MotionTokens.MEDIUM))
            } else {
                fadeOut(motion.exit())
            }
        },
    ) {
        composable(Routes.SIGNIN) {
            SignInScreen(
                relock = false,
                onUnlocked = { nav.navigate(Routes.MAIN) { popUpTo(Routes.SIGNIN) { inclusive = true } } },
                onSetup = { nav.navigate(Routes.setup(false)) },
                onVaultProblem = { nav.navigate(Routes.setup(true)) },
            )
        }
        composable(Routes.SETUP, arguments = listOf(navArgument("vaultOnly") { type = NavType.BoolType; defaultValue = false })) { entry ->
            SetupScreen(
                vaultOnly = entry.arguments?.getBoolean("vaultOnly") ?: false,
                onDone = { nav.navigate(Routes.MAIN) { popUpTo(0) } },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(Routes.MAIN) {
            val s = session ?: return@composable
            CircularReveal { MainScaffold(graph, s, nav, actions) }
        }
        composable(
            Routes.FILE,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val s = session ?: return@composable
            val path = entry.arguments?.getString("path").orEmpty()
            FileRoute(
                session = s,
                path = path,
                query = entry.arguments?.getString("q")?.ifEmpty { null },
                showBack = true,
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.file(it)) },
            )
        }
        composable(Routes.TRASH) {
            val s = session ?: return@composable
            TrashScreen(s, onBack = { nav.popBackStack() })
        }
        composable(Routes.FOLDERS) {
            val s = session ?: return@composable
            FoldersScreen(s, onBack = { nav.popBackStack() })
        }
    }
}

/** Bottom bar on phones; navigation rail with list/detail on tablets and unfolded foldables (≥ 600 dp). */
@Composable
private fun MainScaffold(graph: AppGraph, session: VaultSession, nav: NavHostController, actions: MutableStateFlow<AppAction?>) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var tab by rememberSaveable { mutableStateOf(Tab.NOTES) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val motion = Nss.motion

    fun open(path: String, query: String? = null) {
        if (wide) {
            selected = path
            selectedQuery = query
        } else {
            nav.navigate(Routes.file(path, query))
        }
    }

    // Shortcuts and widget taps.
    val action by actions.collectAsStateWithLifecycle()
    LaunchedEffect(action) {
        when (val a = action) {
            AppAction.NewNote -> {
                tab = Tab.NOTES
                runCatching { session.createNote(session.settings.value.newNoteFolder, "Untitled") }.onSuccess { open(it) }
            }
            AppAction.Search -> {
                tab = Tab.SEARCH
                focusSearch = true
            }
            is AppAction.Open -> open(a.path)
            null -> Unit
        }
        actions.value = null
    }

    val content: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            AnimatedContent(tab, transitionSpec = { motion.fadeThrough() }, label = "tab") { t ->
                when (t) {
                    Tab.NOTES -> TreeScreen(
                        session,
                        selected,
                        onOpen = { open(it) },
                        onOpenTrash = { nav.navigate(Routes.TRASH) },
                        onOpenFolders = { nav.navigate(Routes.FOLDERS) },
                    )
                    Tab.SEARCH -> SearchScreen(session, onOpen = { p, q -> open(p, q) }, autoFocus = focusSearch)
                    Tab.RECENT -> RecentScreen(session, onOpen = { open(it) })
                    Tab.SETTINGS -> SettingsScreen(
                        session,
                        onChangeVault = { nav.navigate(Routes.setup(true)) },
                        onOpenTrash = { nav.navigate(Routes.TRASH) },
                        onOpenFolders = { nav.navigate(Routes.FOLDERS) },
                        onLockNow = { graph.lock.lockNow() },
                        onSignOut = {
                            scope.launch {
                                graph.signOut()
                                nav.navigate(Routes.SIGNIN) { popUpTo(0) }
                            }
                        },
                    )
                }
            }
        }
    }

    GridBackground {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(session, onLock = { graph.lock.lockNow() })
            if (wide) {
                Row(Modifier.weight(1f)) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                        }
                    }
                    content(Modifier.width(380.dp).fillMaxHeight())
                    VerticalDivider(color = Nss.colors.line)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val path = selected
                        if (path == null) {
                            EmptyState(NssIcons.FileText, "Pick a note", "Choose a file on the left to open it here.", Modifier.align(Alignment.Center))
                        } else {
                            FileRoute(session, path, selectedQuery, showBack = false, onBack = { selected = null }, onOpen = { open(it) })
                        }
                    }
                }
            } else {
                content(Modifier.weight(1f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                    }
                }
            }
        }
    }
}

/** Slim header: the Mnemos logo, a Demo badge for the demo user, and Lock now for the owner. */
@Composable
private fun Header(session: VaultSession, onLock: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(style = MaterialTheme.typography.titleMedium, animate = false)
        Spacer(Modifier.width(8.dp))
        if (session.kind == UserKind.DEMO) NssBadge("Demo", Nss.colors.warn)
        Spacer(Modifier.weight(1f))
        if (session.kind == UserKind.OWNER) IconButton(onClick = onLock) { Icon(NssIcons.Lock, contentDescription = "Lock now") }
    }
}

/** Everything a file screen needs. Toggles let a handler switch view (CSV raw ↔ table, big file → edit anyway). */
class FileContext(
    val session: VaultSession,
    val path: String,
    val query: String?,
    val showBack: Boolean,
    val onBack: () -> Unit,
    val onOpen: (String) -> Unit,
    val raw: Boolean,
    val setRaw: (Boolean) -> Unit,
    val editAnyway: Boolean,
    val setEditAnyway: (Boolean) -> Unit,
)

/**
 * One file type's screen. To add a viewer: implement this and put it in [fileHandlers]
 * (first match wins). Conflict copies are handled before any of these.
 */
interface FileTypeHandler {
    fun handles(kind: FileKind, ctx: FileContext): Boolean

    @Composable
    fun Content(ctx: FileContext)
}

private object TableHandler : FileTypeHandler {
    override fun handles(kind: FileKind, ctx: FileContext) = kind == FileKind.CSV && !ctx.raw

    @Composable
    override fun Content(ctx: FileContext) = TableScreen(ctx.session, ctx.path, ctx.showBack, ctx.onBack, onRaw = { ctx.setRaw(true) })
}

private object LargeTextHandler : FileTypeHandler {
    override fun handles(kind: FileKind, ctx: FileContext) = kind == FileKind.LARGE_TEXT && !ctx.editAnyway

    @Composable
    override fun Content(ctx: FileContext) = LargeTextScreen(ctx.session, ctx.path, ctx.showBack, ctx.onBack, onEditAnyway = { ctx.setEditAnyway(true) })
}

private object EditorHandler : FileTypeHandler {
    override fun handles(kind: FileKind, ctx: FileContext) = kind in setOf(FileKind.MARKDOWN, FileKind.TEXT, FileKind.CSV, FileKind.LARGE_TEXT)

    @Composable
    override fun Content(ctx: FileContext) = EditorScreen(
        ctx.session, ctx.path, ctx.query, editAnyway = ctx.editAnyway, ctx.showBack, ctx.onBack,
        onOpenPath = ctx.onOpen, onOpenTable = { ctx.setRaw(false) },
    )
}

private object ImageHandler : FileTypeHandler {
    override fun handles(kind: FileKind, ctx: FileContext) = kind == FileKind.IMAGE

    @Composable
    override fun Content(ctx: FileContext) = ImageScreen(ctx.session, ctx.path, ctx.showBack, ctx.onBack)
}

private object BinaryHandler : FileTypeHandler {
    override fun handles(kind: FileKind, ctx: FileContext) = kind == FileKind.BINARY

    @Composable
    override fun Content(ctx: FileContext) = BinaryScreen(ctx.session, ctx.path, ctx.showBack, ctx.onBack)
}

/** The registry: order matters (table before the editor's raw CSV view, big-file viewer before "edit anyway"). */
private val fileHandlers: List<FileTypeHandler> = listOf(TableHandler, LargeTextHandler, EditorHandler, ImageHandler, BinaryHandler)

/** Picks the right screen for a file by content (text vs binary, size, then extension). */
@Composable
fun FileRoute(session: VaultSession, path: String, query: String?, showBack: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    var raw by rememberSaveable(path) { mutableStateOf(false) }
    var editAnyway by rememberSaveable(path) { mutableStateOf(false) }
    if (ConflictFiles.isConflict(path)) {
        ConflictScreen(session, path, onBack = onBack, onOpen = onOpen)
        return
    }
    // null while loading; a null kind inside means the file is gone or unreadable.
    val kind by produceState<Result<FileKind?>?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { session.fs.stat(path)?.let { FileKinds.classify(session.fs, it) } } }
    }
    val loaded = kind
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val k = loaded.getOrNull()
    if (k == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(NssIcons.Warning, "Can't open this file", loaded.exceptionOrNull()?.message ?: "It was moved or deleted by another app.")
        }
        return
    }
    val ctx = FileContext(session, path, query, showBack, onBack, onOpen, raw, { raw = it }, editAnyway, { editAnyway = it })
    fileHandlers.first { it.handles(k, ctx) }.Content(ctx)
}
