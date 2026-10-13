package com.nihars.mnemos.core.data

import android.content.Context
import java.io.File

/** The bundled sample vault, copied from assets/demo into app-private storage on first use. */
class DemoVault(private val context: Context) {
    val dir: File = File(context.filesDir, "demo-vault")
    private val marker = File(context.filesDir, "demo-vault.ready")

    /** Copies the demo once; later calls are free. */
    fun ensure(): File {
        if (!marker.exists() || !dir.exists()) reset()
        return dir
    }

    /** Settings → Reset demo: wipe the app-private copy and copy it fresh (the real vault is never touched). */
    fun reset() {
        dir.deleteRecursively()
        copy("demo", dir)
        marker.writeText("1")
    }

    private fun copy(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copy("$assetPath/$child", File(target, child))
    }
}
