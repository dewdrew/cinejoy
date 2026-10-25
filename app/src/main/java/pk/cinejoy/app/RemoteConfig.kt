package pk.cinejoy.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Where the site currently lives. */
data class SiteConfig(
    val homeUrl: String,
    val mirrors: List<String>,
    val firstPartyDomains: List<String>
) {
    /** Addresses to try in order on first load: home, then each mirror. */
    val candidateUrls: List<String>
        get() = (listOf(homeUrl) + mirrors).distinct()
}

/**
 * Site address served from the GitHub repo rather than baked into the APK.
 *
 * Streaming sites change domains often, and usually without a redirect. With the
 * address hardcoded, every sideloaded copy would break until it was rebuilt and
 * reinstalled by hand. The config lives on GitHub because it must survive the very
 * domain it describes going away.
 *
 * Resolution order: the last config that downloaded and validated, then
 * [FALLBACK]. A fresh copy is fetched in the background on every launch.
 */
object RemoteConfig {

    const val CONFIG_URL =
        "https://raw.githubusercontent.com/dewdrew/cinejoy/master/config.json"

    /** Used until a remote config has ever been fetched, and if one is unusable. */
    val FALLBACK = SiteConfig(
        homeUrl = "https://cinejoy.pk/",
        mirrors = listOf("https://cinejoy.to/"),
        firstPartyDomains = listOf("cinejoy.pk", "cinejoy.to")
    )

    private const val PREFS = "remote_config"
    private const val KEY_JSON = "config_json"

    /** Bump only alongside an app change that understands the new shape. */
    private const val SUPPORTED_SCHEMA = 1

    private const val TIMEOUT_MS = 6_000

    /** The real file is a few hundred bytes; anything larger is not our config. */
    private const val MAX_BYTES = 64 * 1024

    /** The cached config, or [FALLBACK]. Cheap and synchronous, so safe in onCreate. */
    fun cached(context: Context): SiteConfig {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?: return FALLBACK
        return parse(json) ?: FALLBACK
    }

    /**
     * Download, validate and cache the remote config off the main thread.
     * [onUpdated] runs on the main thread, and only when a valid config arrived;
     * any failure leaves the cache untouched.
     */
    fun refresh(context: Context, onUpdated: (SiteConfig) -> Unit) {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())

        val worker = Thread({
            val body = download() ?: return@Thread
            val config = parse(body) ?: return@Thread
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, body)
                .apply()
            mainHandler.post { onUpdated(config) }
        }, "cinejoy-config")
        worker.isDaemon = true
        worker.start()
    }

    /**
     * Validate a config document. Returns null for anything unusable, so a broken
     * edit on GitHub can never replace a working cached copy.
     */
    fun parse(json: String): SiteConfig? {
        return try {
            val root = JSONObject(json)
            if (root.optInt("schemaVersion", -1) != SUPPORTED_SCHEMA) return null

            val homeUrl = root.optString("homeUrl").trim()
            if (!isHttpsUrl(homeUrl)) return null

            val mirrors = strings(root.optJSONArray("mirrors"))
                .map { it.trim() }
                .filter { isHttpsUrl(it) }

            // Every address the app may load is first-party, whether or not the file
            // remembered to list its host.
            val candidateHosts = (listOf(homeUrl) + mirrors)
                .mapNotNull { Uri.parse(it).host?.lowercase() }
            val listedDomains = strings(root.optJSONArray("firstPartyDomains"))
                .map { it.trim().lowercase() }
                .filter { it.contains('.') }

            SiteConfig(homeUrl, mirrors, (listedDomains + candidateHosts).distinct())
        } catch (_: Exception) {
            null
        }
    }

    private fun download(): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { readCapped(it) } ?: return null
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Read at most [MAX_BYTES]; null if the body is larger. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Only https: the address is loaded with JavaScript on, so never downgrade it. */
    private fun isHttpsUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
    }
}
