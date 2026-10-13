package com.nihars.mnemos.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.nihars.mnemos.core.design.LogoColors

/**
 * Switches the home-screen icon to match the logo colour by enabling one activity-alias and
 * disabling the rest. Some launchers take a moment to refresh, or move the icon back to the
 * app drawer; that is the launcher's doing, not a lost app.
 */
object LauncherIcon {
    private fun alias(context: Context, key: String) =
        ComponentName(context.packageName, "com.nihars.mnemos.app.Launcher" + key.replaceFirstChar { it.uppercase() })

    fun apply(context: Context, logoColor: String) {
        val want = LogoColors.presetFor(logoColor).key
        val pm = context.packageManager
        // Enable the new one first so there is never a moment with no launcher entry.
        set(pm, alias(context, want), true)
        for (p in LogoColors.presets) if (p.key != want) set(pm, alias(context, p.key), false)
    }

    private fun set(pm: PackageManager, c: ComponentName, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(c) == state) return
        pm.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP)
    }
}
