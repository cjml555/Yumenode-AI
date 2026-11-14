// Yumenode AI · (c) 2026 visitor257 · MIT License
// CrashHandler: Java/ART-level exception capture (including UnsatisfiedLinkError), written to file for later inspection.
package com.yumenode.ai

import android.content.Context
import java.io.File

class CrashHandler(private val context: Context) {

    /** Install the global uncaught-exception hook. Recommended in Application#onCreate or Activity#onCreate as early as possible. */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val dump = "${java.util.Date()}\nthread=${t.name}\n" + android.util.Log.getStackTraceString(e)
            // Internal directory (readable back from the UI)
            try {
                File(context.filesDir, "crash.txt").writeText(dump)
            } catch (_: Exception) {}
            // External directory (/sdcard/Android/data/com.yumenode.ai/files/crash.txt; can pull it via MTP on a USB cable)
            try {
                val extDir = context.getExternalFilesDir(null)
                if (extDir != null) {
                    File(extDir, "crash.txt").writeText(dump)
                }
            } catch (_: Exception) {}
        }
    }

    /** Read and clear the crash log (shown on the Models page) */
    fun takeCrashLog(): String? {
        val f = File(context.filesDir, "crash.txt")
        if (!f.exists()) return null
        val s = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        return s
    }
}