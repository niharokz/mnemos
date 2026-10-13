package com.nihars.mnemos.core.data

/**
 * The only logger. Messages carry counts, timings and vault-relative paths, never note
 * contents or the PIN. Debug lines only appear when the tag is enabled (`adb shell setprop log.tag.Mnemos DEBUG`).
 */
object Log {
    private const val TAG = "Mnemos"

    fun d(msg: () -> String) {
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) android.util.Log.d(TAG, msg())
    }

    fun w(msg: String, t: Throwable? = null) {
        android.util.Log.w(TAG, msg + (t?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    fun e(msg: String, t: Throwable? = null) {
        android.util.Log.e(TAG, msg + (t?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
}
