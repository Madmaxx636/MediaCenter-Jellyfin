package dev.mediacenter.jf.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.discard
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalSerializationApi::class)
val JellyfinJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = object : JsonNamingStrategy {
        override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String) =
            serialName.replaceFirstChar { it.uppercaseChar() }
    }
}

/**
 * Thin HTTP layer over the Jellyfin REST API (10.9+). Everything above this
 * works with [BaseItem] and never sees URLs or headers.
 */
class JellyfinApi(private val deviceId: String, private val deviceName: String) {

    val client by lazy { createClient() }

    private fun createClient() = HttpClient(OkHttp) {
        engine { config { Tls.apply(this) } }
        expectSuccess = true
        install(ContentNegotiation) { json(JellyfinJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 30_000
        }
    }

    fun authorization(token: String?) = buildString {
        append("MediaBrowser Client=\"Media Center\", Device=\"")
        append(deviceName.replace("\"", ""))
        append("\", DeviceId=\"").append(deviceId)
        append("\", Version=\"").append(ClientVersion).append('"')
        if (token != null) append(", Token=\"").append(token).append('"')
    }

    private fun HttpRequestBuilder.auth(token: String?) = header(HttpHeaders.Authorization, authorization(token))

    /**
     * Accepts what a person would type ("192.168.1.5", "jelly.home:8096", "https://...")
     * and finds the server. All the likely spellings are tried at once with a short
     * timeout; the first to answer wins.
     */
    suspend fun findServer(input: String): Pair<String, PublicSystemInfo> = coroutineScope {
        val raw = input.trim().trimEnd('/')
        val candidates = if ("://" in raw) listOf(raw) else buildList {
            val hasPort = raw.substringAfterLast(']').contains(':')
            if (!hasPort) add("http://$raw:8096")
            add("http://$raw")
            add("https://$raw")
        }
        val pending = candidates.map { url ->
            async(Dispatchers.IO) {
                runCatching {
                    withTimeout(5_000) { url to client.get("$url/System/Info/Public") { auth(null) }.body<PublicSystemInfo>() }
                }
            }
        }.toMutableList()
        val errors = mutableListOf<Throwable>()
        while (pending.isNotEmpty()) {
            val (done, result) = select { pending.forEach { d -> d.onAwait { d to it } } }
            pending.remove(done)
            result.onSuccess { found ->
                pending.forEach { it.cancel() }
                return@coroutineScope found
            }
            result.exceptionOrNull()?.let(errors::add)
        }
        // Report the most telling failure: something answered but isn't Jellyfin, then a secure
        // connection problem, then anything else. A timeout is reported as an ordinary error, not
        // a cancellation, so the sign-in screen shows it instead of silently doing nothing.
        throw errors.firstOrNull { it is io.ktor.client.plugins.ResponseException || it is kotlinx.serialization.SerializationException }
            ?: errors.firstOrNull { it is javax.net.ssl.SSLException || it.cause is javax.net.ssl.SSLException }
            ?: errors.firstOrNull { it !is kotlinx.coroutines.CancellationException }
            ?: java.net.SocketTimeoutException(if (errors.isEmpty()) "No server address" else "timeout")
    }

    /**
     * Looks for Jellyfin servers on the local network, the way Jellyfin's own apps do:
     * a UDP broadcast of "who is JellyfinServer?" to port 7359, to which each server
     * answers with its name and address. Collects answers for [listenMs]; never throws.
     */
    suspend fun discover(listenMs: Long = 1_500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250
                val ask = "who is JellyfinServer?".toByteArray()
                // The general broadcast address, plus each network's own (some routers only pass those).
                val targets = buildSet {
                    add(java.net.InetAddress.getByName("255.255.255.255"))
                    runCatching {
                        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                            .filter { it.isUp && !it.isLoopback }
                            .flatMap { it.interfaceAddresses }
                            .mapNotNullTo(this) { it.broadcast }
                    }
                }
                // Twice over, as UDP can drop a packet.
                repeat(2) { targets.forEach { runCatching { socket.send(java.net.DatagramPacket(ask, ask.size, it, 7359)) } } }
                val found = linkedMapOf<String, DiscoveredServer>()
                val until = System.currentTimeMillis() + listenMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    runCatching { JellyfinJson.decodeFromString<DiscoveredServer>(String(packet.data, 0, packet.length)) }
                        .getOrNull()
                        ?.takeIf { it.address.isNotBlank() }
                        ?.let { found[it.id.ifEmpty { it.address }] = it }
                }
                found.values.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** Downloads [bytes] of Jellyfin's test data (/Playback/BitrateTest) and returns how many arrived. */
    suspend fun bitrateTest(s: Session, bytes: Int): Long = withContext(Dispatchers.IO) {
        client.prepareGet(s.serverUrl + "/Playback/BitrateTest") {
            header(HttpHeaders.Authorization, authorization(s.token))
            parameter("size", bytes)
        }.execute { it.bodyAsChannel().discard() }
    }

    suspend fun publicUsers(server: String): List<UserDto> =
        client.get("$server/Users/Public") { auth(null) }.body()

    suspend fun authenticate(server: String, user: String, password: String): AuthResult =
        client.post("$server/Users/AuthenticateByName") {
            auth(null)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("Username", user); put("Pw", password) })
        }.body()

    suspend fun quickConnectInitiate(server: String): QuickConnectState =
        client.post("$server/QuickConnect/Initiate") { auth(null) }.body()

    suspend fun quickConnectPoll(server: String, secret: String): QuickConnectState =
        client.get("$server/QuickConnect/Connect") { auth(null); parameter("secret", secret) }.body()

    suspend fun quickConnectAuthenticate(server: String, secret: String): AuthResult =
        client.post("$server/Users/AuthenticateWithQuickConnect") {
            auth(null)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("Secret", secret) })
        }.body()

    // --- Authenticated calls -------------------------------------------------

    suspend inline fun <reified T> get(s: Session, path: String, params: Map<String, Any?> = emptyMap()): T =
        withContext(Dispatchers.IO) {
            try {
                client.get(s.serverUrl + path) {
                    header(HttpHeaders.Authorization, authorization(s.token))
                    params.forEach { (k, v) -> if (v != null) parameter(k, v) }
                }.body()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) dev.mediacenter.jf.AppLog.w("Api", "GET $path failed: ${e.message}")
                throw e
            }
        }

    suspend inline fun <reified T> post(s: Session, path: String, params: Map<String, Any?> = emptyMap(), body: JsonObject? = null): T =
        withContext(Dispatchers.IO) {
            try {
                client.post(s.serverUrl + path) {
                    header(HttpHeaders.Authorization, authorization(s.token))
                    params.forEach { (k, v) -> if (v != null) parameter(k, v) }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }.body()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) dev.mediacenter.jf.AppLog.w("Api", "POST $path failed: ${e.message}")
                throw e
            }
        }

    /** POST a plain-text body (used for client log upload). */
    suspend fun postText(s: Session, path: String, text: String): JsonObject = withContext(Dispatchers.IO) {
        client.post(s.serverUrl + path) {
            header(HttpHeaders.Authorization, authorization(s.token))
            contentType(ContentType.Text.Plain)
            setBody(text)
        }.body()
    }

    /** Downloads a file the server only gives a signed-in user (trickplay sheets, plugin files). */
    suspend fun bytes(s: Session, path: String, params: Map<String, Any?> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
        val response = client.get(s.serverUrl + path) {
            header(HttpHeaders.Authorization, authorization(s.token))
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
        }
        if (response.status.value !in 200..299) error("HTTP ${response.status.value} for $path")
        response.body()
    }

    suspend fun delete(s: Session, path: String, params: Map<String, Any?> = emptyMap()) {
        client.delete(s.serverUrl + path) {
            header(HttpHeaders.Authorization, authorization(s.token))
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
        }
    }

    companion object {
        const val ClientVersion = "0.1.0"
    }
}
