package com.nihars.mnemos.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nihars.mnemos.app.widget.WidgetUpdater
import com.nihars.mnemos.core.data.AppGraph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Builds the one AppGraph and connects app-wide events: background/foreground, screen off, widgets. */
class MnemosApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.onVaultChanged = { WidgetUpdater.updateAll(this) }

        // Auto-lock timer runs from when the whole app leaves the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = graph.lock.onBackground()
            override fun onStart(owner: LifecycleOwner) = graph.lock.onForeground()
        })

        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = graph.lock.onScreenOff()
        }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)

        // The home-screen icon follows Settings → Logo colour (owner's setting).
        graph.scope.launch {
            graph.ownerSettings.settings.map { it.appearance.logoColor }.distinctUntilChanged().collect { LauncherIcon.apply(this@MnemosApp, it) }
        }

        // Widgets hide note content while locked (if the privacy setting is on).
        graph.scope.launch { graph.lock.state.drop(1).collect { WidgetUpdater.updateAll(this@MnemosApp) } }
    }
}

val Context.graph: AppGraph get() = (applicationContext as MnemosApp).graph
