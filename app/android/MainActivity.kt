package ar.nih.mnemostxt

// mnemostxt's Android activity (copied over Tauri's generated one by
// scripts/android-prepare.py).
//  - Asks for "All files access" so the notes folder can live in shared
//    storage (e.g. /storage/emulated/0/notes, synced by Syncthing).
//  - Pads the web view by the system bars and the keyboard, so nothing hides
//    under them (Android 15 always draws edge to edge).

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = findViewById<View>(android.R.id.content)
    root.setBackgroundColor(Color.parseColor("#070B0F"))
    WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      WindowInsetsCompat.CONSUMED
    }
    askForFileAccess()
  }

  private fun askForFileAccess() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      if (Environment.isExternalStorageManager()) return
      try {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
      } catch (e: Exception) {
        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      }
    } else {
      val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
      if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(perms, 1)
    }
  }
}
