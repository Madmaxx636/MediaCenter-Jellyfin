package dev.mediacenter.jf.data

import kotlinx.serialization.Serializable

/*
 * What the Media Center plugin on a Jellyfin server tells the app (GET /MediaCenter/Client):
 * settings set (and perhaps locked) for this user, notices for the start menu, the newest app
 * version, and the server's branding. Servers without the plugin simply don't answer.
 */

@Serializable
data class ServerSetting(val key: String = "", val value: String = "", val locked: Boolean = false)

@Serializable
data class ServerNotice(val id: String = "", val title: String = "", val text: String = "")

@Serializable
data class ServerUpdate(val version: String = "", val url: String = "", val notes: String = "")

@Serializable
data class ServerAsset(val name: String = "", val contentType: String = "", val size: Long = 0, val hash: String = "")

@Serializable
data class ServerBranding(
    val introTitle: String = "",
    val introSubtitle: String = "",
    val accentColor: String = "",
    val assets: List<ServerAsset> = emptyList(),
)

@Serializable
data class ServerClientConfig(
    val pluginVersion: String = "",
    val settings: List<ServerSetting> = emptyList(),
    val notices: List<ServerNotice> = emptyList(),
    val update: ServerUpdate? = null,
    val branding: ServerBranding = ServerBranding(),
    val catalogVersion: String = "",
)

/** The app's settings, as it reports them to the plugin (POST /MediaCenter/Catalog). */
@Serializable
data class SettingsCatalog(val appVersion: String, val settings: List<CatalogEntry>)

@Serializable
data class CatalogEntry(
    val key: String,
    val title: String,
    val help: String,
    val section: String,
    val type: String,
    val default: String,
    val choices: List<CatalogChoice>,
)

@Serializable
data class CatalogChoice(val value: String, val label: String)
