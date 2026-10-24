package pk.cinejoy.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Collections

/**
 * Network-layer shield for popup / redirect ad networks.
 *
 * Streaming pages embed third-party players that fire `window.open()` popups and
 * `intent://` redirects. On a Fire TV Stick a redirect throws the viewer out into
 * the Silk Browser or the Appstore, so the cheapest fix is to never let the
 * request leave the device.
 *
 * The blocklist is deliberately host-based rather than pattern-based: matching on
 * substrings like "ads" would also kill legitimate player assets (`/js/adaptive.js`,
 * hosts such as `cdn-loads.example`), which breaks playback.
 */
object AdBlocker {

    /** Known popup / redirect ad networks. Extend with [block] at runtime. */
    private val blockedDomains: MutableSet<String> = Collections.synchronizedSet(
        mutableSetOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adsterra.com",
            "exoclick.com",
            "juicyads.com",
            "clickadu.com",
            "hilltopads.net",
            "onclickads.net",
            "trafficjunky.net"
        )
    )

    /**
     * Hosts that must never be blocked, whatever else matches. `cinejoy.pk` and all
     * of its subdomains are first-party, so they are always allowed.
     */
    private val allowedDomains: MutableSet<String> = Collections.synchronizedSet(
        mutableSetOf("cinejoy.pk")
    )

    /**
     * Streaming payload extensions. A request for a manifest or a media segment is
     * always let through so HLS / DASH / progressive MP4 playback keeps working even
     * if the CDN hostname resembles an ad host.
     */
    private val mediaExtensions = listOf(
        ".m3u8", ".mpd", ".ts", ".m4s", ".mp4", ".m4v", ".webm", ".mkv",
        ".mp3", ".m4a", ".aac", ".vtt", ".srt", ".key"
    )

    /** True when [url] belongs to a known ad network and carries no media payload. */
    fun isAdDomain(url: String): Boolean {
        val uri = parse(url) ?: return false
        val host = uri.host?.lowercase() ?: return false

        // First-party traffic is never touched.
        if (matches(host, allowedDomains)) return false

        // Never starve the video pipeline, even on an otherwise-blocked host.
        if (isMediaRequest(uri)) return false

        return matches(host, blockedDomains)
    }

    /** True when the URI points at a manifest, media segment, or subtitle track. */
    fun isMediaRequest(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return mediaExtensions.any { path.endsWith(it) }
    }

    /** True when [url] is first-party `cinejoy.pk` traffic. */
    fun isFirstParty(url: String): Boolean {
        val host = parse(url)?.host?.lowercase() ?: return false
        return matches(host, allowedDomains)
    }

    /**
     * An empty `text/plain` response. Returned from `shouldInterceptRequest` so a
     * blocked request resolves instantly instead of hanging or surfacing an error in
     * the page.
     */
    fun createEmptyResource(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Add an ad host at runtime, e.g. from a remote blocklist update. */
    fun block(domain: String) {
        val normalised = normalise(domain)
        if (normalised.isNotEmpty()) blockedDomains.add(normalised)
    }

    /** Allow a host that would otherwise match the blocklist. */
    fun allow(domain: String) {
        val normalised = normalise(domain)
        if (normalised.isNotEmpty()) allowedDomains.add(normalised)
    }

    /** Snapshot of the active blocklist, for diagnostics. */
    fun blockedDomainSnapshot(): Set<String> = synchronized(blockedDomains) {
        blockedDomains.toSet()
    }

    private fun matches(host: String, domains: Set<String>): Boolean = synchronized(domains) {
        domains.any { host == it || host.endsWith(".$it") }
    }

    private fun normalise(domain: String): String =
        domain.trim().lowercase().removePrefix("*.").removePrefix(".")

    private fun parse(url: String): Uri? = try {
        Uri.parse(url)
    } catch (_: Exception) {
        null
    }
}
