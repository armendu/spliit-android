package app.spliit.android.feature.groups

import app.spliit.android.BuildConfig
import java.net.URI

/**
 * Where a group lives when nothing else says otherwise: what a group is created on, and what a
 * bare group ID is looked up against.
 *
 * A `BuildConfig` field rather than a literal, so a build can be pointed at a throwaway instance
 * with the `spliit.baseUrl` property. Settings overrides it, so a screen should read the value
 * from the state it is given rather than from here.
 */
val DEFAULT_INSTANCE_BASE_URL: String = BuildConfig.DEFAULT_INSTANCE_BASE_URL

/**
 * Turns an instance's address into the base URL shape a stored row holds, and back into something
 * short enough to put in a row. Those URLs are compared as plain strings, so the normalisation
 * here is what keeps two spellings of one server from becoming two rows in the list.
 */
object InstanceAddress {

    /**
     * A short label for [baseUrl], its host and port, without the scheme or a trailing slash -
     * for the one place a row names the server a group is on.
     */
    fun displayName(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        val host = uri?.host ?: return baseUrl
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val pathSuffix = uri.path?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: ""
        return host + port + pathSuffix
    }

    /**
     * Normalises a typed server address, `spliit.example.com`, `10.0.2.2:3009`, or a full URL -
     * into a base URL, or null when it names no host at all.
     *
     * A scheme is assumed (`https://`) when none was typed, since that is how people actually
     * type an address out, nobody prefixes a scheme onto a Wi-Fi router label.
     */
    fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        val segments = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
        return baseUrlOf(uri, segments)
    }

    /** [uri]'s scheme and host, followed by [pathSegments] as the instance's own path prefix. */
    internal fun baseUrlOf(uri: URI, pathSegments: List<String>): String {
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = checkNotNull(uri.host) { "baseUrlOf requires a URI with a host" }.lowercase()
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = if (pathSegments.isEmpty()) "/" else "/" + pathSegments.joinToString("/") + "/"
        return "$scheme://$host$port$path"
    }
}
