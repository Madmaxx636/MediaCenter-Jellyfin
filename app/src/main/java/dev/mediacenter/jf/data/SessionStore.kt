package dev.mediacenter.jf.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

data class Session(
    val serverUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val token: String,
    val serverId: String = "",
)

/** A user who has signed in on this device. The access token lets them back in without a password. */
@Serializable
data class SavedUser(val id: String, val name: String, val token: String, val imageTag: String? = null)

/** A server added on this device, named after what the server calls itself (or a name given here). */
@Serializable
data class SavedServer(
    val id: String,
    val name: String,
    val url: String,
    val users: List<SavedUser> = emptyList(),
    val lastUsed: Long = 0,
    /** A name given on this device, shown instead of the server's own. */
    val nickname: String? = null,
) {
    /** What to call this server on screen. */
    val label: String get() = nickname ?: name
}

/** A Jellyfin server that answered on the local network. */
@Serializable
data class DiscoveredServer(val address: String = "", val id: String = "", val name: String = "")

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit { putString("device_id", it) }
    }

    /** Saved servers, most recently used first. Compose state, so the sign-in screen updates live. */
    val servers = mutableStateListOf<SavedServer>().apply {
        addAll(
            runCatching { json.decodeFromString<List<SavedServer>>(prefs.getString("servers", "[]") ?: "[]") }
                .getOrDefault(emptyList()).sortedByDescending { it.lastUsed }
        )
    }

    private fun persistServers() = prefs.edit { putString("servers", json.encodeToString(servers.toList())) }

    var lastServerUrl: String
        get() = prefs.getString("last_server", "") ?: ""
        set(value) = prefs.edit { putString("last_server", value) }

    var lastChannelId: String?
        get() = prefs.getString("last_channel", null)
        set(value) = prefs.edit { putString("last_channel", value) }

    fun load(): Session? {
        val token = prefs.getString("token", null) ?: return null
        return Session(
            serverUrl = prefs.getString("server", null) ?: return null,
            serverName = prefs.getString("server_name", "") ?: "",
            userId = prefs.getString("user_id", null) ?: return null,
            userName = prefs.getString("user_name", "") ?: "",
            token = token,
            serverId = prefs.getString("server_id", "") ?: "",
        )
    }

    fun save(session: Session, imageTag: String? = null) {
        prefs.edit {
            putString("server", session.serverUrl)
            putString("server_name", session.serverName)
            putString("server_id", session.serverId)
            putString("user_id", session.userId)
            putString("user_name", session.userName)
            putString("token", session.token)
            putString("last_server", session.serverUrl)
        }
        remember(session, imageTag)
    }

    /** Adds or refreshes the server and this user's token in the saved list. */
    private fun remember(session: Session, imageTag: String?) {
        val key = session.serverId.ifEmpty { session.serverUrl }
        val existing = servers.firstOrNull { it.id == key || it.url == session.serverUrl }
        val user = SavedUser(session.userId, session.userName, session.token, imageTag)
        val updated = SavedServer(
            id = key,
            name = existing?.name ?: session.serverName.ifEmpty { session.serverUrl },
            url = session.serverUrl,
            users = listOf(user) + existing?.users.orEmpty().filter { it.id != session.userId },
            lastUsed = System.currentTimeMillis(),
            nickname = existing?.nickname,
        )
        servers.remove(existing)
        servers.add(0, updated)
        persistServers()
    }

    /** Saves a server that has been found but not signed into yet. */
    fun addServer(id: String, name: String, url: String): SavedServer {
        val existing = servers.firstOrNull { it.id == id || it.url == url }
        val server = SavedServer(id.ifEmpty { url }, name, url, existing?.users.orEmpty(), System.currentTimeMillis(), existing?.nickname)
        servers.remove(existing)
        servers.add(0, server)
        persistServers()
        return server
    }

    /** Saves a user's sign-in on a server without making them the current user (e.g. signing in a whole household). */
    fun rememberUser(serverId: String, user: SavedUser): SavedServer? {
        val i = servers.indexOfFirst { it.id == serverId }
        if (i < 0) return null
        servers[i] = servers[i].copy(users = servers[i].users.filter { it.id != user.id } + user)
        persistServers()
        return servers[i]
    }

    /** Names a server on this device; blank goes back to the server's own name. */
    fun rename(serverId: String, nickname: String) {
        val i = servers.indexOfFirst { it.id == serverId }
        if (i < 0) return
        servers[i] = servers[i].copy(nickname = nickname.trim().ifEmpty { null })
        persistServers()
    }

    /** Points a saved server at a new address. Saved sign-ins carry over, as it's the same server. */
    fun setAddress(serverId: String, url: String): SavedServer? {
        val i = servers.indexOfFirst { it.id == serverId }
        if (i < 0) return null
        val old = servers[i].url
        servers[i] = servers[i].copy(url = url)
        persistServers()
        if (prefs.getString("server", null) == old) prefs.edit { putString("server", url) }
        return servers[i]
    }

    fun server(idOrUrl: String): SavedServer? = servers.firstOrNull { it.id == idOrUrl || it.url == idOrUrl }

    fun forgetServer(server: SavedServer) {
        servers.removeAll { it.id == server.id }
        persistServers()
    }

    /** Drops a user's saved token (e.g. it expired), keeping the server. */
    fun forgetUser(serverId: String, userId: String) {
        val i = servers.indexOfFirst { it.id == serverId }
        if (i < 0) return
        servers[i] = servers[i].copy(users = servers[i].users.filter { it.id != userId })
        persistServers()
    }

    fun clear() = prefs.edit {
        remove("token")
        remove("user_id")
        remove("user_name")
    }
}
