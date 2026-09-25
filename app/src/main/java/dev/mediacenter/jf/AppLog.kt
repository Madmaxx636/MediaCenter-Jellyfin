package dev.mediacenter.jf

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling log of what the app did (playback, formats, errors, failed
 * requests), kept in memory and sent to the Jellyfin server on request. Crashes
 * are also written to disk so they can be sent after a restart. Access tokens
 * and API keys are stripped from every line.
 */
object AppLog {
    private const val MaxLines = 1_000
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var crashFile: File? = null

    private val secrets = Regex("""(api_key|apikey|token|x-emby-token|pw|password)(["']?\s*[=:]\s*["']?)([^&\s"',}]+)""", RegexOption.IGNORE_CASE)

    fun sanitize(text: String) = secrets.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}***" }

    @Synchronized
    private fun add(level: Char, tag: String, message: String) {
        val line = "${time.format(Date())} $level/$tag: ${sanitize(message)}"
        lines.addLast(line)
        while (lines.size > MaxLines) lines.removeFirst()
        Log.println(if (level == 'E') Log.ERROR else if (level == 'W') Log.WARN else Log.INFO, "MC/$tag", line)
    }

    fun i(tag: String, message: String) = add('I', tag, message)
    fun w(tag: String, message: String) = add('W', tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        add('E', tag, if (error == null) message else "$message\n${stackTrace(error)}")

    private fun stackTrace(t: Throwable) = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** Saves uncaught crashes (with the recent log) so they can be sent next time. */
    fun installCrashHandler(context: Context) {
        crashFile = File(context.filesDir, "last_crash.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                e("Crash", "Uncaught exception on ${thread.name}", error)
                crashFile?.writeText(report(includeCrash = false))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    val hasCrash get() = crashFile?.exists() == true

    fun clearCrash() {
        crashFile?.delete()
    }

    /** The full report: device and app details, any saved crash, then the recent log. */
    @Synchronized
    fun report(includeCrash: Boolean = true, serverName: String? = null): String = buildString {
        appendLine("Media Center for Jellyfin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("CPU: ${Build.SUPPORTED_ABIS.joinToString()}, ${Runtime.getRuntime().availableProcessors()} cores, heap ${Runtime.getRuntime().maxMemory() / 1_048_576} MB")
        if (serverName != null) appendLine("Server: $serverName")
        appendLine("Time: ${Date()}")
        if (includeCrash) crashFile?.takeIf { it.exists() }?.let {
            appendLine()
            appendLine("===== Previous crash =====")
            appendLine(sanitize(it.readText()))
        }
        appendLine()
        appendLine("===== Recent log =====")
        lines.forEach(::appendLine)
    }
}
