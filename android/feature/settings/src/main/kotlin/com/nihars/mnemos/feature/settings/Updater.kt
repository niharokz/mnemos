package com.nihars.mnemos.feature.settings

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import androidx.core.content.IntentCompat
import com.nihars.mnemos.core.data.VaultSession
import com.nihars.mnemos.core.vaultfs.VaultPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** A newer APK found in the vault, already checked to be ours and signed with our key. */
data class UpdateCandidate(val vaultPath: String, val file: File, val versionName: String, val versionCode: Long, val changelog: String)

/**
 * Offline updates: copy `mnemos-*.apk` into `<vault>/apps/` on the laptop, Syncthing brings it
 * over, and this installs it after checking package, version and signing certificate.
 */
object Updater {
    sealed interface Check {
        data class Found(val update: UpdateCandidate) : Check
        data class UpToDate(val message: String) : Check
        data class Problem(val message: String) : Check
    }

    suspend fun check(context: Context, session: VaultSession): Check = withContext(Dispatchers.IO) {
        val folder = session.settings.value.updateFolder.trim().trim('/')
        val apks = runCatching { session.fs.list(folder) }.getOrNull()
            ?.filter { !it.isDirectory && it.name.startsWith("mnemos-") && it.name.endsWith(".apk") }
            ?: return@withContext Check.UpToDate("No “$folder” folder in the vault.")
        if (apks.isEmpty()) return@withContext Check.UpToDate("No mnemos-*.apk in “$folder”.")
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val problems = ArrayList<String>()
        var best: UpdateCandidate? = null
        for (apk in apks) {
            // PackageManager needs a real file; copy to app cache (works for SAF too).
            val local = File(context.cacheDir, "update-${apk.name}")
            local.writeBytes(session.fs.readBytes(apk.path))
            val info = pm.getPackageArchiveInfo(local.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            val reason = when {
                info == null -> "${apk.name} is not a valid APK."
                info.packageName != context.packageName -> "${apk.name} is a different app (${info.packageName})."
                info.longVersionCode <= installed.longVersionCode -> "${apk.name} is not newer (version ${info.versionName})."
                !sameSigner(installed, info) -> "${apk.name} is signed with a different key. Refusing to install."
                else -> null
            }
            if (reason != null || info == null) {
                problems += reason ?: "${apk.name} is not valid."
                local.delete()
                continue
            }
            if (best == null || info.longVersionCode > best.versionCode) {
                best?.file?.delete()
                best = UpdateCandidate(apk.path, local, info.versionName ?: "?", info.longVersionCode, changelogFor(local, info.versionName ?: ""))
            } else {
                local.delete()
            }
        }
        best?.let { Check.Found(it) }
            ?: if (problems.any { it.contains("different key") || it.contains("different app") || it.contains("not a valid") }) {
                Check.Problem(problems.joinToString("\n"))
            } else {
                Check.UpToDate(problems.firstOrNull() ?: "You have the latest version.")
            }
    }

    /** True when the user still has to allow installs from Mnemos (Settings → Install unknown apps). */
    fun needsPermission(context: Context): Boolean = !context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, update: UpdateCandidate) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("mnemos.apk", 0, update.file.length()).use { out ->
                update.file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    private fun sameSigner(a: PackageInfo, b: PackageInfo): Boolean {
        val x = a.signingInfo?.apkContentsSigners ?: return false
        val y = b.signingInfo?.apkContentsSigners ?: return false
        return x.isNotEmpty() && x.size == y.size && x.zip(y).all { (p, q) -> p == q }
    }

    // Reads the bundled CHANGELOG.md from the new APK and returns that version's section.
    private fun changelogFor(apk: File, version: String): String = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("assets/CHANGELOG.md") ?: return@use ""
            val text = zip.getInputStream(entry).bufferedReader().readText()
            val start = text.indexOf("## $version").takeIf { it >= 0 } ?: text.indexOf("## [$version]")
            if (start < 0) return@use ""
            val next = text.indexOf("\n## ", start + 3)
            text.substring(start, if (next < 0) text.length else next).trim()
        }
    }.getOrDefault("")

    /** Shown in Settings so the user knows which file was found. */
    fun label(update: UpdateCandidate): String = "${VaultPaths.name(update.vaultPath)} → ${update.versionName}"
}

/** PackageInstaller status: ask the user to confirm, or report the outcome. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                android.util.Log.w("Mnemos", "update install failed: $msg")
                lastError = msg
            }
        }
    }

    companion object {
        @Volatile var lastError: String? = null
    }
}
