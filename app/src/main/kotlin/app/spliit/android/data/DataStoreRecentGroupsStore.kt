package app.spliit.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
import java.time.Instant

private val Context.recentGroupsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_groups",
)

private val SNAPSHOT_KEY = stringPreferencesKey("snapshot")

public class DataStoreRecentGroupsStore(
    private val dataStore: DataStore<Preferences>,
) : RecentGroupsStore {
    public constructor(context: Context) : this(context.recentGroupsDataStore)

    override suspend fun load(): RecentGroupsSnapshot {
        val json = try {
            dataStore.data.first()[SNAPSHOT_KEY]
        } catch (_: IOException) {
            return RecentGroupsSnapshot()
        } ?: return RecentGroupsSnapshot()
        return try {
            StoredJson.decodeFromString(StoredSnapshot.serializer(), json).toCore()
        } catch (_: Exception) {
            RecentGroupsSnapshot()
        }
    }

    override suspend fun save(snapshot: RecentGroupsSnapshot): Boolean {
        val json = StoredJson.encodeToString(StoredSnapshot.serializer(), StoredSnapshot.from(snapshot))
        return try {
            dataStore.edit { prefs -> prefs[SNAPSHOT_KEY] = json }
            true
        } catch (_: IOException) {
            false
        }
    }
}

@Serializable
private data class StoredSnapshot(
    val groups: List<StoredGroup> = emptyList(),
    val tombstones: Map<String, String> = emptyMap(),
) {
    fun toCore(): RecentGroupsSnapshot = RecentGroupsSnapshot(
        groups = groups.map { it.toCore() },
        tombstones = tombstones.mapValues { (_, iso) -> Instant.parse(iso) },
    )

    companion object {
        fun from(snapshot: RecentGroupsSnapshot): StoredSnapshot = StoredSnapshot(
            groups = snapshot.groups.map(StoredGroup::from),
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
    fun toCore(): RecentGroup = RecentGroup(
        groupId = groupId,
        instanceBaseUrl = instanceBaseUrl,
        groupName = groupName,
        isStarred = isStarred,
        isArchived = isArchived,
        participantId = participantId,
        defaultSplit = defaultSplit?.toCore(),
        lastOpenedAt = lastOpenedAt?.let(Instant::parse),
        updatedAt = updatedAt?.let(Instant::parse),
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
    fun toCore(): DefaultSplit = DefaultSplit(splitMode = SplitMode.valueOf(splitMode), shares = shares)

    companion object {
        fun from(split: DefaultSplit): StoredSplit =
            StoredSplit(splitMode = split.splitMode.name, shares = split.shares)
    }
}
