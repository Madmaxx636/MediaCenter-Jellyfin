package dev.mediacenter.jf.data

import android.content.Context
import android.os.Build
import dev.mediacenter.jf.AppLog
import dev.mediacenter.jf.R
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Android 7.0 and older don't trust Let's Encrypt's root, which most home Jellyfin servers
 * use for HTTPS, so on those TVs the app couldn't connect at all. On those versions only,
 * the two Let's Encrypt roots shipped with the app are trusted alongside the TV's own list,
 * for the API, artwork and video streams alike. Newer Android is left exactly as it is.
 */
object Tls {
    private var factory: SSLSocketFactory? = null
    private var trustManager: X509TrustManager? = null

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= 25) return
        runCatching {
            val system = defaultTrustManager(null)
            val extra = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                val cf = CertificateFactory.getInstance("X.509")
                listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, res ->
                    context.resources.openRawResource(res).use { setCertificateEntry("isrg$i", cf.generateCertificate(it)) }
                }
            }
            val bundled = defaultTrustManager(extra)
            val combined = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        system.checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        bundled.checkServerTrusted(chain, authType)
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + bundled.acceptedIssuers
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }
            factory = ssl.socketFactory
            trustManager = combined
            // Video streams go through HttpURLConnection, which takes the process-wide default.
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            AppLog.i("Tls", "Trusting bundled Let's Encrypt roots on Android ${Build.VERSION.RELEASE}")
        }.onFailure { AppLog.w("Tls", "Couldn't add bundled roots: ${it.message}") }
    }

    /** Applies the same trust to an OkHttp client (the API and artwork clients). */
    fun apply(builder: OkHttpClient.Builder) {
        val f = factory ?: return
        val tm = trustManager ?: return
        builder.sslSocketFactory(f, tm)
    }

    private fun defaultTrustManager(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
}
