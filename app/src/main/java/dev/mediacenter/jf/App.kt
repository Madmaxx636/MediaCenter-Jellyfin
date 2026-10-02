package dev.mediacenter.jf

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import dev.mediacenter.jf.data.BaseItem
import dev.mediacenter.jf.data.DemoRepository
import dev.mediacenter.jf.data.JellyfinApi
import dev.mediacenter.jf.data.JellyfinRepository
import dev.mediacenter.jf.data.MediaRepository
import dev.mediacenter.jf.data.Session
import dev.mediacenter.jf.data.SessionStore
import dev.mediacenter.jf.data.Settings
import dev.mediacenter.jf.playback.PlaybackManager
import dev.mediacenter.jf.ui.Navigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application(), SingletonImageLoader.Factory {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.installCrashHandler(this)
        AppLog.i("App", "Started ${BuildConfig.VERSION_NAME}")
        Hardware.init(this)
        dev.mediacenter.jf.data.Tls.init(this)
        // Find out what this TV decodes in the background, ready for the first playback.
        dev.mediacenter.jf.playback.DeviceCodecs.init(this)
        dev.mediacenter.jf.playback.AutoTune.init(this)
        state = AppState(this)
        // Draw the backdrop and ribbon glows on a background thread while the intro plays.
        dev.mediacenter.jf.ui.theme.Prewarm.start(this, state.settings.uiScale.value, state.settings.textSize.value)
    }

    override fun newImageLoader(context: PlatformContext) =
        ImageLoader.Builder(context)
            .crossfade(250)
            // Artwork over the same connection setup as the API (including the older-Android HTTPS fix).
            .components {
                add(coil3.network.ktor3.KtorNetworkFetcherFactory(httpClient = {
                    io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) { engine { config { dev.mediacenter.jf.data.Tls.apply(this) } } }
                }))
            }
            // Decoded artwork kept in memory, sized to the TV: more on roomy ones, less on 1 GB boxes.
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, Hardware.imageMemoryFraction)
                    .build()
            }
            // A large on-disk artwork cache: posters seen before come back instantly instead of re-downloading.
            // Capped at a tenth of the free storage, for boxes that are short of space.
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(Hardware.artworkDiskBytes(context))
                    .build()
            }
            .build()

    /**
     * Android is short of memory, or the app has gone to the background: let go of what can be
     * rebuilt (artwork held in memory, the start menu's ribbon glow, an idle player), so the
     * app isn't the one that gets closed on a small box.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        AppLog.i("App", "Trimming memory (level $level)")
        SingletonImageLoader.get(this).memoryCache?.clear()
        dev.mediacenter.jf.ui.components.clearGlassCache()
        dev.mediacenter.jf.ui.theme.RibbonCache.clear()
        if (::state.isInitialized) state.playback.releaseIfIdle()
    }
}

/** App-wide objects. There is one per process; screens reach it through [LocalAppState]. */
class AppState(private val app: App) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = SessionStore(app)
    val api = JellyfinApi(store.deviceId, Build.MODEL ?: "Android TV")
    val settings = Settings(app).also { s ->
        // The wallpaper and its colour, read wherever they're drawn.
        dev.mediacenter.jf.ui.theme.Wmc.paletteKey = { s.wallpaperColour.value }
        dev.mediacenter.jf.ui.theme.Wmc.wallpaperKey = { s.wallpaper.value }
    }

    /**
     * The Media Center plugin's settings and branding for the server in use, as last known (before
     * the sounds, so a server's own chime plays from the first frame of the intro).
     */
    val serverControl = ServerControl(app, settings).apply {
        use(store.load()?.let { s -> store.server(s.serverId.ifEmpty { s.serverUrl })?.id })
    }
    val sounds = Sounds(app, { settings.sounds.value }) { name -> serverControl.soundFile(name) }
    val navigator = Navigator()

    /** The startup animation plays once per launch. */
    var introShown by mutableStateOf(false)

    /** Runs 0 → 1 as the intro hands over to the screen underneath; the start menu slides in with it. */
    var introHandoff by mutableFloatStateOf(if (settings.intro.value) 0f else 1f)

    /** Set once the first screen underneath the intro has been built and drawn; the intro waits for it. */
    var introMenuReady by mutableStateOf(false)

    /** True while the intro covers the whole screen, so the screen underneath needn't be drawn. */
    var introCovers by mutableStateOf(false)

    /** The Media Center intro settles the menu in from slightly too large, rather than sliding it in. */
    var introZoomMenu by mutableStateOf(false)

    /** The corner logo's fade-in during the Media Center intro (the other intros fly the orb onto it). */
    var introLogoAlpha by mutableFloatStateOf(0f)

    /** What's been loaded for the person signed in, kept compressed across switching users and servers. */
    val accountCache = dev.mediacenter.jf.data.AccountCache(app)

    /** The cache's name for whoever is signed in on a saved server (null for the demo, or signed out). */
    private fun accountKey(): String? {
        if (repository?.isDemo != false) return null
        val session = store.load() ?: return null
        val server = store.server(session.serverId.ifEmpty { session.serverUrl })?.id ?: return null
        return dev.mediacenter.jf.data.AccountCache.key(server, session.userId)
    }

    /** Switching to someone else: what this person has loaded is kept, compressed, for when they're back. */
    private fun keepAccount() {
        val key = accountKey() ?: return
        scope.launch { accountCache.saveAndClear(key) }
    }

    /** Finds and installs newer versions of the app from GitHub. */
    val updater = Updater(app)

    /** The hold-OK menu that's open, if any (see ItemMenu.kt). */
    var menu by mutableStateOf<dev.mediacenter.jf.ui.screens.MenuSheet?>(null)

    /** The screensaver is showing (after a while idle, per settings › general). */
    var screensaver by mutableStateOf(false)

    /** When a button was last pressed, for the screensaver's idle time. */
    var lastInputAt = android.os.SystemClock.elapsedRealtime()

    /**
     * Left idle for hours with nothing playing: the app stops keeping the TV awake, so the TV's own sleep
     * (and power saving) still happens overnight. Any button, or something playing, clears it.
     */
    var idleLong by mutableStateOf(false)

    /** A short confirmation along the bottom of the screen ("Added … to the queue"). */
    var toast by mutableStateOf<String?>(null)

    /** Watched and favourite marks changed from a hold-OK menu, shown until the lists are loaded again. */
    val userDataEdits = androidx.compose.runtime.mutableStateMapOf<String, dev.mediacenter.jf.data.UserData>()

    /** Where the corner logo sits on screen, so the intro's orb can land exactly on it. */
    var logoBounds: androidx.compose.ui.geometry.Rect? = null

    fun finishIntro() {
        introShown = true
        introCovers = false
        introHandoff = 1f
        introLogoAlpha = 1f
    }

    var repository by mutableStateOf<MediaRepository?>(store.load()?.let { JellyfinRepository(api, it, store.deviceId, settings) })
        private set

    val playback = PlaybackManager(app, scope, settings) { repository }.apply {
        onChannelChanged = { store.lastChannelId = it.id }
    }

    /** Starts live TV on [channel], or on the last channel watched. */
    fun watchLiveTv(channels: List<BaseItem>, channel: BaseItem? = null) {
        if (channels.isEmpty()) return
        val start = channel ?: channels.firstOrNull { it.id == store.lastChannelId } ?: channels.first()
        playback.play(channels, channels.indexOfFirst { it.id == start.id }.coerceAtLeast(0), resume = false)
        navigator.showPlayer()
    }

    init {
        // What "automatic" works out to, shown beside it in settings.
        fun speed(live: Boolean): String? {
            val key = currentServer()?.id ?: return null
            if (dev.mediacenter.jf.playback.AutoTune.measuredBps(key) == null) return "not measured yet"
            return dev.mediacenter.jf.playback.AutoTune.bitrateLabel(dev.mediacenter.jf.playback.AutoTune.autoBitrate(key, live))
        }
        settings.maxBitrate.autoDetail = { speed(false) }
        settings.liveBitrate.autoDetail = { speed(true) }
        settings.maxResolution.autoDetail = { dev.mediacenter.jf.playback.AutoTune.heightLabel(dev.mediacenter.jf.playback.AutoTune.autoMaxHeight()) + " screen" }
        settings.liveResolution.autoDetail = settings.maxResolution.autoDetail
        settings.surround.autoDetail = { if (dev.mediacenter.jf.playback.AutoTune.autoStereo()) "stereo" else "surround" }
        serverControl.onSoundsChanged = { sounds.reloadServerSounds() }
        afterSignIn()
        refreshServerControl(force = true)
    }

    /** Asks the server's Media Center plugin (if it has one) for its settings, notices and branding. */
    fun refreshServerControl(force: Boolean = false) {
        val repo = repository ?: return
        val key = currentServer()?.id
        if (key == null) {
            serverControl.use(null)
            return
        }
        scope.launch { serverControl.refresh(repo, key, force) }
    }

    /**
     * On a server this TV hasn't been set up for, "optimize for this TV" comes up once, over the
     * start menu; on one it has, the connection is re-measured quietly when the reading is old.
     */
    private fun afterSignIn() {
        val repo = repository ?: return
        val key = currentServer()?.id ?: return
        if (!dev.mediacenter.jf.playback.AutoTune.isTuned(key)) {
            navigator.push(dev.mediacenter.jf.ui.OptimizeDest(firstRun = true))
        } else {
            scope.launch { dev.mediacenter.jf.playback.AutoTune.refreshIfStale(repo, key) }
        }
    }

    /** Set by "switch users": the sign-in screen opens on this server's user list. */
    var switchingServer by mutableStateOf<dev.mediacenter.jf.data.SavedServer?>(null)

    fun signIn(session: Session, imageTag: String? = null, resetNavigation: Boolean = true) {
        store.save(session, imageTag)
        switchingServer = null
        settings.showDemo.set(false)
        repository = JellyfinRepository(api, session, store.deviceId, settings)
        // Back to someone kept when switching away: their lists come back from the compressed file at once.
        accountKey()?.let { key -> accountCache.restoring = scope.launch { accountCache.restore(key) } }
        if (resetNavigation) {
            navigator.reset()
            afterSignIn()
        }
        refreshServerControl(force = true)
    }

    fun startDemo() {
        repository = DemoRepository(java.io.File(app.filesDir, "demo.mkv"))
        serverControl.use(null)
        navigator.reset()
    }

    /** Sends the log (and any saved crash) to the Jellyfin server; returns the file name it was saved as. */
    suspend fun sendLog(): String {
        val repo = repository ?: error("Not connected to a server")
        val name = repo.uploadLog(AppLog.report(serverName = repo.serverName))
        AppLog.clearCrash()
        AppLog.i("Log", "Sent to server as $name")
        return name
    }

    fun signOut() {
        accountCache.clear()
        playback.stop()
        store.clear()
        repository = null
        serverControl.use(null)
        navigator.reset()
    }

    /**
     * "Close" on the start menu: shut down (the app closes completely: playback stopped, the app taken off
     * the recent apps and its process ended, so it starts afresh next time) or minimize (back to the TV's home;
     * the app is kept as it is and opens where you left it).
     */
    fun showCloseMenu(activity: android.app.Activity) {
        menu = dev.mediacenter.jf.ui.screens.MenuSheet(
            "close Media Center", null,
            listOf(
                dev.mediacenter.jf.ui.screens.MenuChoice("minimize", dev.mediacenter.jf.ui.components.Glyph.Minus) { activity.moveTaskToBack(true) },
                dev.mediacenter.jf.ui.screens.MenuChoice("shut down", dev.mediacenter.jf.ui.components.Glyph.Exit) { shutDown(activity) },
            ),
        )
        sounds.select()
    }

    private suspend fun shutDown(activity: android.app.Activity) {
        AppLog.i("App", "Shut down")
        if (playback.nowPlaying.value != null) {
            playback.stop()
            // A moment for the server to hear that playback stopped (it closes a conversion or a live tuner then).
            kotlinx.coroutines.delay(600)
        }
        activity.finishAndRemoveTask()
        kotlinx.coroutines.delay(300)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** The saved server currently in use, if any (not the demo). */
    fun currentServer(): dev.mediacenter.jf.data.SavedServer? {
        val current = store.load() ?: return null
        if (repository?.isDemo != false) return null
        return store.server(current.serverId.ifEmpty { current.serverUrl })
    }

    /** Back to the list of saved servers. */
    fun switchServer() {
        keepAccount()
        switchingServer = null
        signOut()
    }

    /** Straight to [server]'s "who's watching" list. */
    fun openServer(server: dev.mediacenter.jf.data.SavedServer) {
        keepAccount()
        signOut()
        switchingServer = server
    }

    /** Points a saved server at a new address; if it's the one in use, carries on there without a restart. */
    fun setServerAddress(server: dev.mediacenter.jf.data.SavedServer, url: String) {
        val inUse = currentServer()?.id == server.id
        store.setAddress(server.id, url) ?: return
        val current = store.load()
        if (inUse && current != null) signIn(current.copy(serverUrl = url), resetNavigation = false)
    }

    /** Forgets a server and everyone saved on it; if it's the one in use, goes back to the server list. */
    fun forgetServer(server: dev.mediacenter.jf.data.SavedServer) {
        val inUse = currentServer()?.id == server.id
        store.forgetServer(server)
        accountCache.forget(server.id + "_")
        if (inUse) switchServer()
    }

    /** Signs a saved user out on this device; if it's whoever is watching now, goes back to "who's watching". */
    fun forgetUser(server: dev.mediacenter.jf.data.SavedServer, user: dev.mediacenter.jf.data.SavedUser) {
        val you = currentServer()?.id == server.id && store.load()?.userId == user.id
        store.forgetUser(server.id, user.id)
        accountCache.forget(dev.mediacenter.jf.data.AccountCache.key(server.id, user.id))
        if (you) switchUser()
    }

    /** Back to the "who's watching" list of the server you're on (or the server list, from the demo). */
    fun switchUser() {
        keepAccount()
        val current = store.load()
        val repo = repository
        switchingServer = if (repo?.isDemo == false && current != null) store.server(current.serverId.ifEmpty { current.serverUrl }) else null
        signOut()
    }
}

val LocalAppState = staticCompositionLocalOf<AppState> { error("AppState not provided") }
