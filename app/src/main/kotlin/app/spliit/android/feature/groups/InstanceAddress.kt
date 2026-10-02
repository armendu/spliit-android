package app.spliit.android.feature.groups

import app.spliit.android.BuildConfig
import java.net.URI

val DEFAULT_INSTANCE_BASE_URL: String = BuildConfig.DEFAULT_INSTANCE_BASE_URL

object InstanceAddress {
    fun displayName(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        val host = uri?.host ?: return baseUrl
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val pathSuffix = uri.path?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "/$it" } ?: ""
        return host + port + pathSuffix
    }

    fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        val segments = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
        return baseUrlOf(uri, segments)
    }

    internal fun baseUrlOf(uri: URI, pathSegments: List<String>): String {
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = checkNotNull(uri.host) { "baseUrlOf requires a URI with a host" }.lowercase()
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val path = if (pathSegments.isEmpty()) "/" else "/" + pathSegments.joinToString("/") + "/"
        return "$scheme://$host$port$path"
    }
}
