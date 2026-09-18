package app.spliit.android.feature.groups

import java.net.URI

/**
 * A group link somebody pasted, parsed into the group it names and — when the link says so — the
 * instance it lives on.
 *
 * Ported from the iOS app's `GroupLink`. It names two things, not one: a group ID means nothing
 * without the server that issued it, so a link naming an instance is how somebody is let into a
 * group on a server this phone has never talked to — most of what self-hosting is. The shapes
 * recognised, all naming the same group:
 *
 * ```
 * https://spliit.app/groups/<id>
 * https://spliit.app/groups/<id>/expenses            what the web app's share link points at
 * https://home.example.com/spliit/groups/<id>        self-hosted, under any path prefix
 * <id>                                                what people paste out of an address bar
 * ```
 *
 * @param instanceBaseUrl The instance the link names, or null for a bare ID — which names none,
 *   and can only mean wherever the app points by default.
 */
data class GroupLink(val groupId: String, val instanceBaseUrl: String?) {

    companion object {
        /** The group a pasted value names, or null for text that is not a group link at all. */
        fun parse(pastedText: String): GroupLink? {
            val trimmed = pastedText.trim()
            if (trimmed.isEmpty()) return null

            // A link copied out of an address bar often arrives with no scheme, and
            // "spliit.example.com/groups/x" would otherwise parse as a path with no host. Only
            // assumed for something with a slash in it — "https://" in front of a bare ID would
            // make the ID itself the hostname.
            val candidate = if (trimmed.contains("://") || !trimmed.contains("/")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            fromUrl(candidate)?.let { return it }

            // A bare ID: no slashes, no spaces, and not a URL fromUrl could make sense of.
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
