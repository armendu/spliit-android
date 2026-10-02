package app.spliit.android.feature.groups

import java.net.URI

data class GroupLink(val groupId: String, val instanceBaseUrl: String?) {
    companion object {
        fun parse(pastedText: String): GroupLink? {
            val trimmed = pastedText.trim()
            if (trimmed.isEmpty()) return null

            val candidate = if (trimmed.contains("://") || !trimmed.contains("/")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            fromUrl(candidate)?.let { return it }

            if (!trimmed.contains("/") && !trimmed.contains(" ")) {
                return GroupLink(groupId = trimmed, instanceBaseUrl = null)
            }
            return null
        }

        private fun fromUrl(text: String): GroupLink? {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null

            val segments = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
            val groupsIndex = segments.indexOf("groups")
            if (groupsIndex < 0 || groupsIndex + 1 !in segments.indices) return null
            val groupId = segments[groupsIndex + 1]
            if (groupId.isBlank()) return null

            val instanceBaseUrl = InstanceAddress.baseUrlOf(uri, segments.subList(0, groupsIndex))
            return GroupLink(groupId = groupId, instanceBaseUrl = instanceBaseUrl)
        }
    }
}
