package dev.mediacenter.jf

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer version of the app on GitHub, and the APK that suits this TV. */
class AppUpdate(val version: String, val notes: String, val apkUrl: String, val apkName: String, val size: Long, val sumsUrl: String?)

/**
 * Keeps the app up to date from its GitHub releases: finds a newer version (at most twice a day,
 * or when asked), downloads the APK for this TV's processor, checks it against the release's
 * checksums, and hands it to Android's installer, which asks before replacing the app.
 * Only release builds update themselves (test builds are a different app).
 */
class Updater(private val context: Context) {
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    val enabled = BuildConfig.BUILD_TYPE == "release"

    /** The newer version, once found. */
    var available by mutableStateOf<AppUpdate?>(null)
        private set

    /** What's happening, for the screen: "checking…", "downloading 42%", "up to date", or what went wrong. */
    var status by mutableStateOf<String?>(null)

    var busy by mutableStateOf(false)
        private set

    private val current get() = BuildConfig.VERSION_NAME.substringBefore('-')

    private val downloads get() = File(context.cacheDir, "updates")

    /** Looks for a newer version: when [force]d, or if the last look was over twelve hours ago. */
    suspend fun check(force: Boolean = false): AppUpdate? {
        if (!enabled || busy) return available
        if (!force && System.currentTimeMillis() - prefs.getLong("checkedAt", 0) < 12 * 3600_000L) return available
        if (force) status = "checking…"
        val found = try {
            withContext(Dispatchers.IO) { latest() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.w("Update", "Couldn't check for a new version: ${e.message}")
            if (force) status = "couldn't check: ${e.message ?: "no connection"}"
            return available
        }
        prefs.edit { putLong("checkedAt", System.currentTimeMillis()) }
        // Up to date: an APK left from installing this version is no longer needed.
        if (found == null) withContext(Dispatchers.IO) { downloads.deleteRecursively() }
        available = found
        if (found != null) AppLog.i("Update", "Media Center ${found.version} is available (${found.apkName})")
        if (force) status = if (found == null) "up to date" else null
        return found
    }

    /** The newest release with an APK for this TV, if it's newer than this app. Pre-releases count: all releases so far are. */
    private fun latest(): AppUpdate? {
        val releases = Json.parseToJsonElement(get("https://api.github.com/repos/$Repo/releases?per_page=10")).jsonArray
        val release = releases.map { it.jsonObject }.firstOrNull { !(it["draft"]?.jsonPrimitive?.boolean ?: false) } ?: return null
        val version = release["tag_name"]!!.jsonPrimitive.content.trimStart('v')
        if (!ServerControl.isNewer(version, current)) return null
        val assets = (release["assets"] as? JsonArray).orEmpty().map { it.jsonObject }
        fun asset(suffix: String) = assets.firstOrNull { it.name.endsWith(suffix) }
        // The APK for this processor; the universal one where there's none of its own.
        val abis = Build.SUPPORTED_ABIS.toList()
        val apk = when {
            abis.firstOrNull()?.startsWith("arm64") == true -> asset("-arm64.apk")
            abis.firstOrNull()?.startsWith("armeabi") == true -> asset("-arm32.apk")
            else -> null
        } ?: asset("-universal.apk") ?: assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        return AppUpdate(
            version = version,
            notes = release["body"]?.jsonPrimitive?.content.orEmpty(),
            apkUrl = apk.url, apkName = apk.name, size = apk["size"]?.jsonPrimitive?.long ?: 0L,
            sumsUrl = asset("SHA256SUMS.txt")?.url,
        )
    }

    /** Downloads [update], checks it, and starts installing it; Android then asks to confirm. */
    suspend fun install(update: AppUpdate) {
        if (busy) return
        busy = true
        try {
            val file = withContext(Dispatchers.IO) { download(update) }
            status = "installing…"
            withContext(Dispatchers.IO) { startInstall(file) }
            // Android's own screen takes over from here (and the app restarts once it's installed).
            status = "confirm on the next screen"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.w("Update", "Update failed: ${e.message}")
            status = "couldn't update: ${e.message}"
        } finally {
            busy = false
        }
    }

    private fun download(update: AppUpdate): File {
        val file = File(downloads.apply { deleteRecursively(); mkdirs() }, update.apkName)
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(update.apkUrl)
        try {
            val length = if (Build.VERSION.SDK_INT >= 24) connection.contentLengthLong else connection.contentLength.toLong()
            val total = length.takeIf { it > 0 } ?: update.size
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) { shown = percent; status = "downloading $percent%" }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        // The release lists each file's checksum; a download that doesn't match isn't installed.
        update.sumsUrl?.let { url ->
            val expected = get(url).lineSequence().map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it.last().trimStart('*') == update.apkName }?.first()?.lowercase()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && expected != actual) {
                file.delete()
                error("the download was damaged (checksum mismatch)")
            }
        }
        // And it must be this app.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("the download isn't an app")
        if (info.packageName != context.packageName) error("the download is a different app (${info.packageName})")
        return file
    }

    private fun startInstall(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(ActionInstalled)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    // --- The start menu's notice ---------------------------------------------------------------

    /** A notice of the newer version for the start menu, until it's installed or put off. */
    val pendingMessage: ServerMessage?
        get() {
            laterVersion // Read here, so putting it off hides the notice straight away.
            val u = available ?: return null
            if (prefs.getBoolean("later:${u.version}", false)) return null
            return ServerMessage(
                "app-update:${u.version}", "Media Center ${u.version} is available",
                "You have ${BuildConfig.VERSION_NAME}. Install it now? It downloads from GitHub, then Android asks you to confirm.",
                action = "install",
            )
        }

    /** Don't mention this version on the start menu again (it's still in settings › about). */
    fun later() {
        available?.let { u -> prefs.edit { putBoolean("later:${u.version}", true) } }
        laterVersion++
    }

    var laterVersion by mutableStateOf(0)
        private set

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "MediaCenter-Jellyfin/${BuildConfig.VERSION_NAME}")
        if (responseCode !in 200..299) error("HTTP $responseCode")
    }

    private fun get(url: String): String {
        val c = open(url)
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private val JsonObject.name get() = this["name"]?.jsonPrimitive?.content.orEmpty()
    private val JsonObject.url get() = this["browser_download_url"]?.jsonPrimitive?.content.orEmpty()

    companion object {
        const val Repo = "Madmaxx636/MediaCenter-Jellyfin"
        const val ActionInstalled = "dev.mediacenter.jf.UPDATE_INSTALLED"
    }
}

/**
 * Hears back from Android's installer: shows its confirmation screen when it needs the viewer's
 * say-so (it always does for an app installed by hand), and notes the outcome.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i("Update", "Installed")
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $result"
                AppLog.w("Update", "Not installed: $why")
                (context.applicationContext as? App)?.state?.updater?.status =
                    if (result == PackageInstaller.STATUS_FAILURE_ABORTED) "update cancelled" else "couldn't install: $why"
            }
        }
    }
}
