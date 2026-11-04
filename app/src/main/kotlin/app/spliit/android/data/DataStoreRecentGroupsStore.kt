package app.spliit.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.spliit.core.DefaultSplit
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.RecentGroupsStore
import app.spliit.core.SplitMode
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.Instant

// Without a handler a damaged file fails every later edit too, so nothing new could ever be stored.
private val Context.recentGroupsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_groups",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val SNAPSHOT_KEY = stringPreferencesKey("snapshot")

// The list is the only way back to a group, so a version that can't read it keeps a copy instead of writing over it.
internal val UNREADABLE_SNAPSHOT_KEY = stringPreferencesKey("snapshot_unreadable")

public class DataStoreRecentGroupsStore(
    private val dataStore: DataStore<Preferences>,
) : RecentGroupsStore {
    public constructor(context: Context) : this(context.recentGroupsDataStore)

    override suspend fun load(): RecentGroupsSnapshot {
        val json = try {
            dataStore.data.first()[SNAPSHOT_KEY]
        } catch (_: IOException) {
            return RecentGroupsSnapshot()
        }
        return decode(json).snapshot
    }

    override suspend fun save(snapshot: RecentGroupsSnapshot): Boolean = update { snapshot } != null

    override suspend fun update(change: (RecentGroupsSnapshot) -> RecentGroupsSnapshot): RecentGroupsSnapshot? {
        var changed: RecentGroupsSnapshot? = null
        return try {
            dataStore.edit { prefs ->
                val stored = prefs[SNAPSHOT_KEY]
                val decoded = decode(stored)
                if (!decoded.isComplete && stored != null && prefs[UNREADABLE_SNAPSHOT_KEY] == null) {
                    prefs[UNREADABLE_SNAPSHOT_KEY] = stored
                }
                val next = change(decoded.snapshot)
                prefs[SNAPSHOT_KEY] = StoredJson.encodeToString(StoredSnapshot.serializer(), StoredSnapshot.from(next))
                changed = next
            }
            changed
        } catch (_: IOException) {
            null
        }
    }

    private class Decoded(val snapshot: RecentGroupsSnapshot, val isComplete: Boolean)

    // Row by row: one group this version can't read costs that group, not the list.
    private fun decode(json: String?): Decoded {
        if (json == null) return Decoded(RecentGroupsSnapshot(), isComplete = true)
        val stored = try {
            StoredJson.decodeFromString(StoredSnapshot.serializer(), json)
        } catch (_: Exception) {
            return Decoded(RecentGroupsSnapshot(), isComplete = false)
        }
        var isComplete = true
        val groups = stored.groups.mapNotNull { element ->
            val group = try {
                StoredJson.decodeFromJsonElement(StoredGroup.serializer(), element).toCore()
            } catch (_: Exception) {
                null
            }
            if (group == null) isComplete = false
            group
        }
        val tombstones = stored.tombstones.mapNotNull { (groupId, iso) ->
            parseInstant(iso)?.let { groupId to it }
        }.toMap()
        if (tombstones.size != stored.tombstones.size) isComplete = false
        return Decoded(RecentGroupsSnapshot(groups, tombstones), isComplete)
    }
}

private fun parseInstant(iso: String): Instant? = try {
    Instant.parse(iso)
} catch (_: Exception) {
    null
}

@Serializable
private data class StoredSnapshot(
    val groups: List<JsonElement> = emptyList(),
    val tombstones: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(snapshot: RecentGroupsSnapshot): StoredSnapshot = StoredSnapshot(
            groups = snapshot.groups.map { StoredJson.encodeToJsonElement(StoredGroup.serializer(), StoredGroup.from(it)) },
            tombstones = snapshot.tombstones.mapValues { (_, instant) -> instant.toString() },
        )
    }
}

@Serializable
private data class StoredGroup(
    val groupId: String,
    val instanceBaseUrl: String,
    val groupName: String,
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val participantId: String? = null,
    val defaultSplit: StoredSplit? = null,
    val lastOpenedAt: String? = null,
    val updatedAt: String? = null,
) {
    // A split or a timestamp this version can't read is dropped; the group itself is kept.
    fun toCore(): RecentGroup = RecentGroup(
        groupId = groupId,
        instanceBaseUrl = instanceBaseUrl,
        groupName = groupName,
        isStarred = isStarred,
        isArchived = isArchived,
        participantId = participantId,
        defaultSplit = defaultSplit?.toCore(),
        lastOpenedAt = lastOpenedAt?.let(::parseInstant),
        updatedAt = updatedAt?.let(::parseInstant),
    )

    companion object {
        fun from(group: RecentGroup): StoredGroup = StoredGroup(
            groupId = group.groupId,
            instanceBaseUrl = group.instanceBaseUrl,
            groupName = group.groupName,
            isStarred = group.isStarred,
            isArchived = group.isArchived,
            participantId = group.participantId,
            defaultSplit = group.defaultSplit?.let(StoredSplit::from),
            lastOpenedAt = group.lastOpenedAt?.toString(),
            updatedAt = group.updatedAt?.toString(),
        )
    }
}

@Serializable
private data class StoredSplit(
    val splitMode: String,
    val shares: Map<String, Long>? = null,
) {
    fun toCore(): DefaultSplit? =
        SplitMode.entries.firstOrNull { it.name == splitMode }?.let { DefaultSplit(splitMode = it, shares = shares) }

    companion object {
        fun from(split: DefaultSplit): StoredSplit =
            StoredSplit(splitMode = split.splitMode.name, shares = split.shares)
    }
}
