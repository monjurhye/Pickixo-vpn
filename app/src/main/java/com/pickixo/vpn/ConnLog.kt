package com.pickixo.vpn

import android.content.Context
import android.os.Build
import android.util.Log
import com.pickixo.vpn.sstp.SstpClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-memory connection log the user can view and copy from the menu (no browsing data),
 * plus a crash catcher that saves the stack trace so it can be shown on the next launch.
 */
object ConnLog {
    private const val MAX = 600
    private const val CRASH_FILE = "last_crash.txt"
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile private var crashDir: File? = null

    fun install(ctx: Context) {
        if (SstpClient.debug == null) SstpClient.debug = java.util.function.Consumer { add(it) }
        if (crashDir != null) return
        crashDir = ctx.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val report = "Pickixo VPN ${BuildConfig.VERSION_NAME} crash on thread '${thread.name}'\n" +
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                    sw + "\n--- last log lines ---\n" + text().lines().takeLast(80).joinToString("\n")
                File(crashDir, CRASH_FILE).writeText(report)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns and deletes the report saved by the last crash, if any. */
    fun takeCrashReport(ctx: Context): String? {
        val f = File(ctx.filesDir, CRASH_FILE)
        if (!f.exists()) return null
        return try { f.readText().also { f.delete() } } catch (e: Exception) { null }
    }

    @Synchronized
    fun add(line: String) {
        Log.d("PickixoVpn", line)
        lines.addLast(fmt.format(Date()) + " " + line)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
}
