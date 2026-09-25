package dev.mediacenter.jf

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import dev.mediacenter.jf.data.CatalogChoice
import dev.mediacenter.jf.data.CatalogEntry
import dev.mediacenter.jf.data.JellyfinJson
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.ServerClientConfig
import dev.mediacenter.jf.data.Settings
import dev.mediacenter.jf.data.SettingsCatalog
import dev.mediacenter.jf.ui.theme.Wmc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A notice for the start menu: from the server, or telling of a newer version of the app. */
data class ServerMessage(val id: String, val title: String, val text: String, val action: String? = null)

/**
 * What the Media Center plugin on the server sets for this app: settings (some locked), notices,
 * update notices, and branding (the startup chime and sounds, logo, intro title, backdrop and
 * accent colour). Kept per server on the TV, so the branding is there from the first frame of the
 * next launch (the chime plays before the server can be asked), and refreshed from the server
 * after signing in and now and then at the start menu. Servers without the plugin change nothing.
 */
class ServerControl(private val context: Context, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("server_control", Context.MODE_PRIVATE)

    /** The server whose settings and branding are in use. */
    private var serverKey: String? = null

    var config by mutableStateOf<ServerClientConfig?>(null)
        private set

    var logo by mutableStateOf<ImageBitmap?>(null)
        private set

    var backdrop by mutableStateOf<ImageBitmap?>(null)
        private set

    val introTitle get() = config?.branding?.introTitle?.takeIf { it.isNotBlank() }
    val introSubtitle get() = config?.branding?.introSubtitle?.takeIf { it.isNotBlank() }

    /** Called when the server's sounds change, so they can be loaded afresh. */
    var onSoundsChanged: () -> Unit = {}

    private var lastRefresh = 0L

    /** The server a refresh is on its way from; asking it again meanwhile would only repeat it. */
    private var refreshing: String? = null

    private fun dir(key: String) = File(context.filesDir, "branding/" + key.filter { it.isLetterOrDigit() })

    /** A branding sound the server has (intro, focus, select, back, error), if any. */
    fun soundFile(name: String): File? {
        val key = serverKey ?: return null
        return File(dir(key), name).takeIf { it.length() > 0 && config?.branding?.assets?.any { a -> a.name == name } == true }
    }

    /** Switches to [key]'s settings and branding (as last known), or none for the demo or signed out. */
    fun use(key: String?) {
        if (key == serverKey && config != null) return
        serverKey = key
        config = key?.let { k -> prefs.getString("config:$k", null)?.let { runCatching { JellyfinJson.decodeFromString<ServerClientConfig>(it) }.getOrNull() } }
        applySettings()
        applyBranding()
    }

    /**
     * Asks the server again: at most every ten minutes unless [force]. Unreachable, it keeps what was
     * known; without the plugin, it lets go of everything the plugin had set.
     */
    suspend fun refresh(repo: MediaRepository, key: String, force: Boolean = false) {
        if (key == refreshing || !force && key == serverKey && System.currentTimeMillis() - lastRefresh < 10 * 60_000L) return
        refreshing = key
        try {
            refreshNow(repo, key)
        } finally {
            if (refreshing == key) refreshing = null
        }
    }

    private suspend fun refreshNow(repo: MediaRepository, key: String) {
        use(key)
        val fresh = try {
            repo.serverControl()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.w("Server", "Couldn't ask the server for its Media Center settings: ${e.message}")
            return
        }
        lastRefresh = System.currentTimeMillis()
        if (key != serverKey) return
        if (fresh == null) {
            if (config != null) AppLog.i("Server", "The Media Center plugin isn't on this server (any more)")
            prefs.edit { remove("config:$key") }
            config = null
            withContext(Dispatchers.IO) { dir(key).deleteRecursively() }
        } else {
            AppLog.i("Server", "Media Center plugin ${fresh.pluginVersion}: ${fresh.settings.size} settings, ${fresh.notices.size} notices, ${fresh.branding.assets.size} branding files")
            fetchAssets(repo, key, fresh)
            prefs.edit { putString("config:$key", JellyfinJson.encodeToString(ServerClientConfig.serializer(), fresh)) }
            config = fresh
            sendCatalogIfNewer(repo, fresh.catalogVersion)
        }
        applySettings()
        applyBranding()
        onSoundsChanged()
    }

    /**
     * The server's settings: locked ones always, and the others each time the server's value
     * changes (so a choice made on the TV afterwards stands until the server sets a new one).
     */
    private fun applySettings() {
        val server = config?.settings.orEmpty().associateBy { it.key }
        settings.catalog.forEach { (_, setting) ->
            val s = server[setting.key]
            setting.lockedByServer = s?.locked == true
            if (s == null) return@forEach
            val appliedKey = "applied:${serverKey}:${s.key}"
            if (s.locked || prefs.getString(appliedKey, null) != s.value) {
                if (setting.setFromText(s.value)) prefs.edit { putString(appliedKey, s.value) }
            }
        }
    }

    private suspend fun fetchAssets(repo: MediaRepository, key: String, fresh: ServerClientConfig) = withContext(Dispatchers.IO) {
        val folder = dir(key).apply { mkdirs() }
        val wanted = fresh.branding.assets.associateBy { it.name }
        folder.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
        wanted.values.forEach { asset ->
            val file = File(folder, asset.name)
            val hashKey = "hash:$key:${asset.name}"
            if (file.length() > 0 && prefs.getString(hashKey, null) == asset.hash) return@forEach
            runCatching {
                file.writeBytes(repo.serverAsset(asset.name))
                prefs.edit { putString(hashKey, asset.hash) }
            }.onFailure { AppLog.w("Server", "Couldn't fetch the server's ${asset.name}: ${it.message}") }
        }
    }

    private fun applyBranding() {
        val key = serverKey
        val names = config?.branding?.assets?.map { it.name }.orEmpty()
        logo = if (key != null && "logo" in names) decode(File(dir(key), "logo"), 512) else null
        backdrop = if (key != null && "backdrop" in names) decode(File(dir(key), "backdrop"), 1920) else null
        Wmc.accent = config?.branding?.accentColor?.let(::parseColor)
    }

    /** Decodes a picture no wider than [maxWidth], so a large upload can't use up a small TV's memory. */
    private fun decode(file: File, maxWidth: Int): ImageBitmap? = runCatching {
        if (file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    private fun parseColor(text: String): Color? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6) return null
        return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }

    /** Sends this app's list of settings when the server has an older one (or none), for its settings page. */
    private suspend fun sendCatalogIfNewer(repo: MediaRepository, serverHas: String) {
        // The version without a build's suffix ("0.9.8-qa" is 0.9.8): the settings are the same.
        val mine = BuildConfig.VERSION_NAME.substringBefore('-')
        if (serverHas.substringBefore('-') == mine || isNewer(serverHas, mine)) return
        val entries = settings.catalog.map { (section, s) ->
            val isSwitch = s.choices.size == 2 && s.choices.all { it.value is Boolean }
            CatalogEntry(
                key = s.key, title = s.title, help = s.help, section = section,
                type = if (isSwitch) "boolean" else "choice", default = s.defaultText,
                choices = if (isSwitch) emptyList() else s.choices.map { CatalogChoice(it.value.toString(), it.label) },
            )
        }
        runCatching { repo.sendSettingsCatalog(SettingsCatalog(mine, entries)) }
            .onSuccess { AppLog.i("Server", "Sent the server this app's ${entries.size} settings") }
            .onFailure { AppLog.w("Server", "Couldn't send the server this app's settings: ${it.message}") }
    }

    // --- Notices -------------------------------------------------------------------------------

    /** The next notice to show on the start menu: the server's, then a newer app version; null for none. */
    val pendingMessage: ServerMessage?
        get() {
            val c = config ?: return null
            c.notices.firstOrNull { !dismissed("notice:${it.id}") }?.let { return ServerMessage("notice:${it.id}", it.title, it.text) }
            val update = c.update ?: return null
            if (!isNewer(update.version, BuildConfig.VERSION_NAME) || dismissed("update:${update.version}")) return null
            return ServerMessage(
                "update:${update.version}", "Media Center ${update.version} is available",
                "You have ${BuildConfig.VERSION_NAME}. Get the new version from\n${update.url.removePrefix("https://")}",
            )
        }

    private var dismissedVersion by mutableStateOf(0)

    private fun dismissed(id: String): Boolean {
        dismissedVersion // Compose reads this, so dismissing shows the next one.
        return prefs.getBoolean("dismissed:$serverKey:$id", false)
    }

    fun dismiss(message: ServerMessage) {
        prefs.edit { putBoolean("dismissed:$serverKey:${message.id}", true) }
        dismissedVersion++
    }

    companion object {
        fun isNewer(candidate: String, than: String): Boolean {
            fun parts(v: String) = v.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(than)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
