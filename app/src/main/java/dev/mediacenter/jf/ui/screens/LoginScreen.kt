package dev.mediacenter.jf.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.mediacenter.jf.AppLog
import dev.mediacenter.jf.LocalAppState
import dev.mediacenter.jf.data.AuthResult
import dev.mediacenter.jf.data.DiscoveredServer
import dev.mediacenter.jf.data.QuickConnectState
import dev.mediacenter.jf.data.SavedServer
import dev.mediacenter.jf.data.SavedUser
import dev.mediacenter.jf.data.Session
import dev.mediacenter.jf.data.UserDto
import dev.mediacenter.jf.ui.ServerSetupDest
import dev.mediacenter.jf.ui.components.ActionButton
import dev.mediacenter.jf.ui.components.BusyIndicator
import dev.mediacenter.jf.ui.components.FocusBox
import dev.mediacenter.jf.ui.components.Glyph
import dev.mediacenter.jf.ui.components.GlyphIcon
import dev.mediacenter.jf.ui.components.ScreenPadH
import dev.mediacenter.jf.ui.components.TopChrome
import dev.mediacenter.jf.ui.components.WText
import dev.mediacenter.jf.ui.components.WmcTextField
import dev.mediacenter.jf.ui.components.aeroGlass
import dev.mediacenter.jf.ui.components.focusWhenReady
import dev.mediacenter.jf.ui.components.tryFocus
import dev.mediacenter.jf.ui.theme.Wmc
import dev.mediacenter.jf.ui.theme.WmcType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Step { Servers, Address, Users, Password, QuickConnect, Household }

/**
 * Sign-in, in the style of Media Center's setup wizard: pick a saved server, one found
 * on the network, or add one by address (it's saved under the server's own name), then
 * pick who's watching. People who've signed in on this device before go straight in.
 *
 * After adding a server, everyone else who uses the TV can be signed in too before
 * carrying on ("who else watches here?"), so they're one press away from then on.
 * With [setup] (from settings, while signed in) it adds a server or signs in more
 * people on one, and whoever is watching stays signed in.
 */
@Composable
fun LoginScreen(setup: ServerSetupDest? = null) {
    val app = LocalAppState.current
    val api = app.api
    val store = app.store
    val scope = rememberCoroutineScope()

    val start = remember {
        when {
            setup != null -> if (setup.serverId?.let { store.server(it) } != null) Step.Household else Step.Address
            app.switchingServer != null -> Step.Users
            store.servers.isNotEmpty() -> Step.Servers
            else -> Step.Address
        }
    }
    var step by remember { mutableStateOf(start) }
    var server by remember { mutableStateOf(setup?.serverId?.let { store.server(it) } ?: app.switchingServer) }
    var publicUsers by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    // Servers that answered on the network; null until the first search finishes.
    var found by remember { mutableStateOf<List<DiscoveredServer>?>(null) }
    // Signing in several people in a row: each is saved, then it's back to "who else watches here?".
    var collecting by remember { mutableStateOf(setup != null) }
    // The first person signed in on a newly added server; "done" carries on as them.
    var lead by remember { mutableStateOf<Session?>(null) }
    var leadTag by remember { mutableStateOf<String?>(null) }
    val address = remember { mutableStateOf("") }
    val username = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf<QuickConnectState?>(null) }
    val first = remember(step) { FocusRequester() }
    val firstFound = remember(step) { FocusRequester() }

    LaunchedEffect(step) {
        error = null
        first.focusWhenReady()
    }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.w("Login", "Sign-in step failed: ${e.message}")
                app.sounds.error()
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun finish(auth: AuthResult) {
        val s = server ?: return
        val session = Session(s.url, s.label, auth.user.id, auth.user.name ?: username.value, auth.accessToken, s.id)
        if (!collecting) {
            app.signIn(session, auth.user.primaryImageTag)
            return
        }
        server = store.rememberUser(s.id, SavedUser(session.userId, session.userName, session.token, auth.user.primaryImageTag)) ?: s
        if (lead == null && setup == null) {
            lead = session
            leadTag = auth.user.primaryImageTag
        }
        step = Step.Household
    }

    /** Opens a saved server's user list, refreshing its name and the public users. */
    suspend fun showUsers(saved: SavedServer) {
        server = saved
        val info = runCatching { api.findServer(saved.url).second }.getOrNull()
        if (info?.serverName != null && info.serverName != saved.name) server = store.addServer(saved.id, info.serverName, saved.url)
        publicUsers = runCatching { api.publicUsers(saved.url) }.getOrDefault(emptyList())
        step = Step.Users
    }

    /** Saves a server just found or typed in; a new one goes on to sign in the household. */
    suspend fun added(id: String, name: String, url: String) {
        val existing = store.server(id) ?: store.server(url)
        if (existing != null && existing.users.isNotEmpty() && setup == null) {
            showUsers(store.addServer(existing.id, name, url))
            return
        }
        server = store.addServer(id, name, url)
        collecting = true
        publicUsers = runCatching { api.publicUsers(url) }.getOrDefault(emptyList())
        step = Step.Users
    }

    fun openServer(saved: SavedServer) = run { showUsers(saved) }

    fun connect() = run {
        val (url, info) = api.findServer(address.value)
        added(info.id ?: url, info.serverName ?: url, url)
    }

    fun pickFound(d: DiscoveredServer) = run {
        val saved = store.server(d.id)
        if (saved != null && setup == null) {
            showUsers(saved)
        } else {
            val (url, info) = api.findServer(d.address)
            added(info.id ?: d.id, info.serverName ?: d.name, url)
        }
    }

    /** A user who signed in here before: reuse their token, or ask for the password if it's no longer valid. */
    fun resume(saved: SavedUser) = run {
        val s = server ?: return@run
        val session = Session(s.url, s.label, saved.id, saved.name, saved.token, s.id)
        val ok = runCatching { api.get<UserDto>(session, "/Users/Me") }.isSuccess
        if (ok) {
            app.signIn(session, saved.imageTag)
        } else {
            store.forgetUser(s.id, saved.id)
            server = store.server(s.id)
            username.value = saved.name
            password.value = ""
            step = Step.Password
            error = "Please sign in again: your saved sign-in for ${saved.name} has expired."
        }
    }

    fun signIn() = run {
        val url = server?.url ?: return@run
        finish(api.authenticate(url, username.value.trim(), password.value))
    }

    /** Leaves "who else watches here?": carries on as the first person, or back to settings. */
    fun householdDone() {
        if (setup != null) {
            app.navigator.pop()
        } else {
            val l = lead
            if (l != null) app.signIn(l, leadTag) else step = Step.Users
        }
    }

    // Load the user list when arriving here from "switch users", or for "sign in more people".
    LaunchedEffect(Unit) {
        val s = server ?: return@LaunchedEffect
        publicUsers = runCatching { api.publicUsers(s.url) }.getOrDefault(emptyList())
    }
    // The people arrive a moment after the screen: move focus onto the first of them.
    LaunchedEffect(publicUsers) {
        if (publicUsers.isNotEmpty() && (step == Step.Users || step == Step.Household)) first.focusWhenReady()
    }

    // Look for servers on the network while choosing or adding one, and keep looking.
    val searching = step == Step.Servers || step == Step.Address
    LaunchedEffect(searching) {
        if (!searching) return@LaunchedEffect
        while (true) {
            val results = api.discover()
            val firstTime = found.isNullOrEmpty()
            found = results
            // Nothing typed yet: a server found on the network is the easier pick, so offer it first.
            if (firstTime && results.isNotEmpty() && step == Step.Address && address.value.isEmpty()) firstFound.tryFocus()
            delay(8_000)
        }
    }

    BackHandler(enabled = step != start || step == Step.Household || (step == Step.Users && app.switchingServer != null && setup == null)) {
        app.sounds.back()
        when (step) {
            Step.Password, Step.QuickConnect ->
                step = if (collecting && (lead != null || setup != null)) Step.Household else Step.Users
            Step.Household -> householdDone()
            Step.Users -> {
                app.switchingServer = null
                step = when {
                    setup != null -> Step.Address
                    store.servers.isNotEmpty() -> Step.Servers
                    else -> Step.Address
                }
            }
            Step.Address -> if (store.servers.isNotEmpty() && setup == null) step = Step.Servers
            Step.Servers -> Unit
        }
    }

    if (step == Step.QuickConnect) {
        LaunchedEffect(Unit) {
            val url = server?.url ?: return@LaunchedEffect
            try {
                var state = api.quickConnectInitiate(url)
                quick = state
                while (!state.authenticated) {
                    delay(3_000)
                    state = api.quickConnectPoll(url, state.secret)
                }
                finish(api.quickConnectAuthenticate(url, state.secret))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "Quick Connect isn't available: ${friendly(e)}"
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        TopChrome(showBack = step != start || setup != null)
        val wide = step == Step.Servers || step == Step.Address || step == Step.Users || step == Step.Household
        Column(
            Modifier.padding(start = ScreenPadH + 40.dp, end = ScreenPadH, top = 96.dp)
                .then(if (wide) Modifier.fillMaxWidth() else Modifier.width(680.dp))
                .aeroGlass(corner = 10.dp, strong = true).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val serverName = server?.label ?: "your server"
            when (step) {
                Step.Servers -> {
                    WText("choose a server", WmcType.Hero)
                    WText("Your saved servers, and any Jellyfin servers found on your network. Rename or remove them in settings › servers.", WmcType.Body, maxLines = 2)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        store.servers.forEachIndexed { i, saved ->
                            ServerTile(
                                saved, if (i == 0) Modifier.focusRequester(first) else Modifier,
                                onClick = { openServer(saved) },
                                onForget = { store.forgetServer(saved) },
                            )
                        }
                        AddTile("add server", Glyph.Plus, if (store.servers.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                            address.value = ""; step = Step.Address
                        }
                        if (app.settings.showDemo.value) AddTile("try the demo", Glyph.Movies) { app.startDemo() }
                    }
                    FoundServers(found, store.servers, firstFound, ::pickFound)
                }
                Step.Address -> {
                    WText(if (store.servers.isEmpty()) "welcome" else "add a server", WmcType.Hero)
                    WText(
                        "Pick a server found on your network, or type its address, like 192.168.1.20 or jellyfin.local:8096.",
                        WmcType.Body, maxLines = 2,
                    )
                    FoundServers(found, store.servers, firstFound, ::pickFound)
                    val nextButton = remember { FocusRequester() }
                    StateField(
                        address, "server address",
                        // Down from the address goes to "next", the obvious next step.
                        Modifier.width(460.dp).focusRequester(first).focusProperties { down = nextButton },
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, onDone = { connect() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("next", { connect() }, Modifier.width(180.dp).focusRequester(nextButton), Glyph.Play)
                        if (app.settings.showDemo.value && store.servers.isEmpty() && setup == null) {
                            ActionButton("try the demo", { app.startDemo() }, Modifier.width(220.dp), Glyph.Movies)
                        }
                    }
                }
                Step.Users -> {
                    WText("who's watching?", WmcType.Hero)
                    WText(serverName, WmcType.Body)
                    val saved = server?.users.orEmpty()
                    val others = publicUsers.filter { p -> saved.none { it.id == p.id } }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        var index = 0
                        saved.forEach { user ->
                            UserTile(
                                user.name, userImage(server, user.id, user.imageTag),
                                if (index++ == 0) Modifier.focusRequester(first) else Modifier, caption = "signed in",
                            ) { resume(user) }
                        }
                        others.forEach { user ->
                            UserTile(
                                user.name ?: "", userImage(server, user.id, user.primaryImageTag),
                                if (index++ == 0) Modifier.focusRequester(first) else Modifier,
                            ) {
                                username.value = user.name ?: ""
                                password.value = ""
                                if (!user.hasPassword) signIn() else step = Step.Password
                            }
                        }
                        UserTile("someone else", null, if (index++ == 0) Modifier.focusRequester(first) else Modifier, glyph = Glyph.User) {
                            username.value = ""; password.value = ""; step = Step.Password
                        }
                        UserTile("quick connect", null, glyph = Glyph.Tv) { step = Step.QuickConnect }
                    }
                    // Right under the people, so it's easy to find (a tile would scroll off past them).
                    if (setup == null) {
                        ActionButton(
                            "switch server", { app.switchingServer = null; step = Step.Servers },
                            Modifier.width(400.dp).padding(top = 4.dp), Glyph.Server,
                            detail = if (store.servers.size > 1) "${store.servers.size} saved" else "add or choose another",
                        )
                    }
                }
                Step.Household -> {
                    WText("who else watches here?", WmcType.Hero)
                    WText(
                        "Sign in everyone who uses this TV on $serverName, and next time they're one press away, with no password.",
                        WmcType.Body, maxLines = 2,
                    )
                    val saved = server?.users.orEmpty()
                    val others = publicUsers.filter { p -> saved.none { it.id == p.id } }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    ) {
                        // The next person to add gets focus, since that's the likely next press.
                        others.forEachIndexed { i, user ->
                            UserTile(
                                user.name ?: "", userImage(server, user.id, user.primaryImageTag),
                                if (i == 0) Modifier.focusRequester(first) else Modifier,
                            ) {
                                username.value = user.name ?: ""
                                password.value = ""
                                if (!user.hasPassword) signIn() else step = Step.Password
                            }
                        }
                        UserTile("someone else", null, if (others.isEmpty()) Modifier.focusRequester(first) else Modifier, glyph = Glyph.User) {
                            username.value = ""; password.value = ""; step = Step.Password
                        }
                        UserTile("quick connect", null, glyph = Glyph.Tv) { step = Step.QuickConnect }
                        saved.forEach { user ->
                            UserTile(user.name, userImage(server, user.id, user.imageTag), caption = "saved ✓") {}
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        val l = lead
                        ActionButton(
                            "done", ::householdDone, Modifier.width(if (l != null) 340.dp else 180.dp), Glyph.Check,
                            detail = l?.let { "continue as ${it.userName}" },
                        )
                        val s = server
                        if (setup != null && s != null && s.users.isNotEmpty() && app.currentServer()?.id != s.id) {
                            ActionButton("switch to ${s.label}", { app.openServer(s) }, Modifier.width(340.dp), Glyph.Server)
                        }
                    }
                }
                Step.Password -> {
                    WText("sign in", WmcType.Hero)
                    WText("Sign in to $serverName. You'll stay signed in on this device, so you won't need your password next time.", WmcType.Body, maxLines = 2)
                    StateField(username, "user name", Modifier.width(460.dp).focusRequester(first))
                    StateField(
                        password, "password", Modifier.width(460.dp), password = true,
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, onDone = { signIn() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("sign in", { signIn() }, Modifier.width(180.dp), Glyph.Play)
                        ActionButton("use quick connect", { step = Step.QuickConnect }, Modifier.width(260.dp), Glyph.Tv)
                    }
                }
                Step.QuickConnect -> {
                    WText("quick connect", WmcType.Hero)
                    WText(
                        "On your phone or computer, open Jellyfin, go to your profile and choose Quick Connect, then enter this code:",
                        WmcType.Body, maxLines = 3,
                    )
                    val code = quick?.code
                    if (code == null) BusyIndicator() else WText(code.chunked(3).joinToString(" "), WmcType.Hero.copy(fontSize = WmcType.Hero.fontSize * 1.5f), color = Wmc.Accent)
                    ActionButton("sign in with a password instead", { step = Step.Password }, Modifier.width(360.dp).focusRequester(first), Glyph.User)
                }
            }
            if (busy) BusyIndicator(size = 36.dp)
            error?.let { WText(it, WmcType.Label, color = Wmc.Warning, maxLines = 3) }
        }
    }
}

/**
 * Jellyfin servers that answered on the network and aren't saved yet: one press to add.
 * [found] is null while the first search is still running.
 */
@Composable
private fun FoundServers(
    found: List<DiscoveredServer>?,
    saved: List<SavedServer>,
    firstRequester: FocusRequester,
    onPick: (DiscoveredServer) -> Unit,
) {
    val fresh = found.orEmpty().filter { d -> saved.none { it.id == d.id || it.url.trimEnd('/') == d.address.trimEnd('/') } }
    WText("found on your network", WmcType.Label, Modifier.padding(top = 6.dp), color = Wmc.TextDim)
    when {
        found == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BusyIndicator(size = 24.dp)
            WText("looking for Jellyfin servers…", WmcType.Caption)
        }
        fresh.isEmpty() -> WText(
            if (found.isEmpty()) "None found yet. Still looking; you can also add a server by its address."
            else "Every server found here is already saved.",
            WmcType.Caption, maxLines = 2,
        )
        else -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            fresh.forEachIndexed { i, d ->
                FoundTile(d, if (i == 0) Modifier.focusRequester(firstRequester) else Modifier) { onPick(d) }
            }
        }
    }
}

@Composable
private fun FoundTile(server: DiscoveredServer, modifier: Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(170.dp)) {
        FocusBox(onClick = onClick, fill = true, scale = 1.06f, corner = 8.dp, modifier = modifier.size(160.dp, 110.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(160.dp, 110.dp).border(1.dp, Wmc.GlassEdge, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.Server, size = 44.dp) }
        }
        WText(server.name.ifEmpty { "Jellyfin" }, WmcType.Label, Modifier.padding(top = 10.dp), maxLines = 1, align = TextAlign.Center)
        WText(server.address.removePrefix("http://").removePrefix("https://"), WmcType.Caption, maxLines = 1, align = TextAlign.Center)
    }
}

private fun userImage(server: SavedServer?, userId: String, tag: String?) =
    server?.url?.let { url -> tag?.let { "$url/Users/$userId/Images/Primary?tag=$it&fillHeight=240" } }

@Composable
private fun ServerTile(saved: SavedServer, modifier: Modifier, onClick: () -> Unit, onForget: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(170.dp)) {
        FocusBox(
            onClick = onClick, onLongClick = onForget, fill = true, scale = 1.06f, corner = 8.dp,
            modifier = modifier.size(160.dp, 110.dp), contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(160.dp, 110.dp).aeroGlass(corner = 8.dp, tint = Wmc.themed(androidx.compose.ui.graphics.Color(0xFF3D7FCC))), contentAlignment = Alignment.Center) {
                dev.mediacenter.jf.ui.components.LogoOrb(size = 56.dp)
            }
        }
        WText(saved.label, WmcType.Label, Modifier.padding(top = 10.dp), maxLines = 1, align = TextAlign.Center)
        WText(
            saved.url.removePrefix("http://").removePrefix("https://") +
                if (saved.users.isNotEmpty()) "  ·  ${saved.users.size} saved" else "",
            WmcType.Caption, maxLines = 1, align = TextAlign.Center,
        )
    }
}

@Composable
private fun AddTile(label: String, glyph: Glyph, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(170.dp)) {
        FocusBox(onClick = onClick, fill = true, scale = 1.06f, corner = 8.dp, modifier = modifier.size(160.dp, 110.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(160.dp, 110.dp).border(1.dp, Wmc.GlassEdge, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(glyph, size = 40.dp) }
        }
        WText(label, WmcType.Label, Modifier.padding(top = 10.dp), maxLines = 1, align = TextAlign.Center)
    }
}

@Composable
private fun UserTile(
    name: String,
    image: String?,
    modifier: Modifier = Modifier,
    glyph: Glyph = Glyph.User,
    caption: String? = null,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        FocusBox(onClick = onClick, corner = 60.dp, scale = 1.1f, modifier = modifier.size(110.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(110.dp).clip(CircleShape).background(Wmc.Glass, CircleShape).border(1.dp, Wmc.GlassEdge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    AsyncImage(model = image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(110.dp))
                } else if (glyph == Glyph.User && name.isNotEmpty() && name != "someone else") {
                    WText(name.take(1).uppercase(), WmcType.Hero)
                } else {
                    GlyphIcon(glyph, size = 44.dp)
                }
            }
        }
        WText(name, WmcType.Label, Modifier.padding(top = 10.dp), maxLines = 1, align = TextAlign.Center)
        if (caption != null) WText(caption, WmcType.Caption, color = Wmc.Accent, align = TextAlign.Center)
    }
}

private fun friendly(e: Exception): String {
    val m = e.message.orEmpty()
    return when {
        "401" in m || "Unauthorized" in m -> "That user name or password isn't right."
        "Expected response body" in m || "404" in m || e is kotlinx.serialization.SerializationException ->
            "There's no Jellyfin server at that address. Check it and try again."
        e is javax.net.ssl.SSLException || e.cause is javax.net.ssl.SSLException || "Certificate" in m || "certificate" in m ->
            "Couldn't make a secure connection: this TV doesn't trust the server's certificate. Try its http:// address on your home network."
        e is java.net.UnknownHostException || "Unable to resolve" in m -> "Couldn't find that server. Check the address."
        e is java.net.ConnectException || "timeout" in m.lowercase() || "Failed to connect" in m ->
            "The server didn't answer. Is it running and on the same network?"
        else -> m.ifEmpty { "Something went wrong." }
    }
}

/** For settings: the same wording as sign-in when checking a server address. */
internal fun friendlyError(e: Exception) = friendly(e)

@Composable
private fun StateField(
    state: MutableState<String>,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) = WmcTextField(state.value, { state.value = it }, label, modifier, password, keyboardType, imeAction, onDone)
