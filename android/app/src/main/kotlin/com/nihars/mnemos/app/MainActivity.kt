package com.nihars.mnemos.app

import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nihars.mnemos.core.security.LockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Something asked of the app from outside: a shortcut, a widget tap, a notification. */
sealed interface AppAction {
    data object NewNote : AppAction
    data object Search : AppAction
    data class Open(val path: String) : AppAction
}

/** Single Activity. FragmentActivity because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {
    private val actions = MutableStateFlow<AppAction?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hidden in Recents by default until settings say otherwise (spec §2.2).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = this.graph

        // Hold the system splash only until we know whether to show the lock (spec §8).
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (graph.lock.state.value == LockState.UNKNOWN) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.lockStore.settings.collect { s ->
                    if (s.hideInRecents) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        handle(intent)
        setContent { AppRoot(graph, actions) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_LOCK -> graph.lock.lockNow()
            ACTION_NEW_NOTE -> actions.value = AppAction.NewNote
            ACTION_SEARCH -> actions.value = AppAction.Search
            ACTION_OPEN -> intent.getStringExtra(EXTRA_PATH)?.let { actions.value = AppAction.Open(it) }
        }
    }

    companion object {
        const val ACTION_NEW_NOTE = "com.nihars.mnemos.NEW_NOTE"
        const val ACTION_SEARCH = "com.nihars.mnemos.SEARCH"
        const val ACTION_LOCK = "com.nihars.mnemos.LOCK"
        const val ACTION_OPEN = "com.nihars.mnemos.OPEN"
        const val EXTRA_PATH = "path"
    }
}
