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

// A thin DataStore adapter for RecentGroupsSnapshot. Thin is load-bearing: every rule about what
// the stored list *is* lives in `:core` and is tested there. An `if` here that decides something
// rather than translating it belongs there instead.
//
// The DTOs are this file's own rather than `@Serializable` versions of `:core`'s, which depends
// on nothing beyond the stdlib and should not gain a serialization dependency for a storage
// format it never reads. The conversions below are the whole cost of that boundary.

/** One process-wide DataStore, opened once per [Context]. */
private val Context.recentGroupsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_groups",
)

/** The single key: the whole snapshot is one StoredJson blob, not one Preferences entry per field. */
private val SNAPSHOT_KEY = stringPreferencesKey("snapshot")

/**
 * The AndroidX DataStore–backed [RecentGroupsStore].
 *
 * @param dataStore Usually `context.recentGroupsDataStore`; taken as a parameter rather than a
 *   [Context] directly so this class touches nothing Android beyond the DataStore type itself.
 */
public class DataStoreRecentGroupsStore(
    private val dataStore: DataStore<Preferences>,
) : RecentGroupsStore {

    public constructor(context: Context) : this(context.recentGroupsDataStore)

    override suspend fun load(): RecentGroupsSnapshot {
        // The read itself, not only the decode: `data` throws IOException, and this runs inside
        // `viewModelScope.launch` with no handler, where that would take the process with it.
        val json = try {
            dataStore.data.first()[SNAPSHOT_KEY]
        } catch (_: IOException) {
            return RecentGroupsSnapshot()
        } ?: return RecentGroupsSnapshot()
        return try {
            // Decoding and converting are one attempt: malformed JSON and a `SplitMode` name
            // from a newer version have to degrade the same way.
            StoredJson.decodeFromString(StoredSnapshot.serializer(), json).toCore()
        } catch (_: Exception) {
            // A blob this device wrote and can no longer parse is worse to crash over than to
            // treat as empty: the list rebuilds as groups are opened, a crash loop does not.
            RecentGroupsSnapshot()
        }
    }

    /**
     * Returns false when the write failed, which callers that have just created a group must
     * act on: there is no account and no server-side list, so a group the server has and this
     * file does not is unreachable for good.
     */
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

// ---- the on-disk shape ---------------------------------------------------------------------

@Serializable
private data class StoredSnapshot(
    val groups: List<StoredGroup> = emptyList(),
    /** Group ID to when it was deleted, each value an ISO-8601 instant (`Instant.toString()`). */
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
    /** Both default to false, so a list written before Part 12 added them reads back unchanged
     *  rather than failing to decode, see the note on `ignoreUnknownKeys` above. */
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val participantId: String? = null,
    val defaultSplit: StoredSplit? = null,
    /** ISO-8601, or null, see [RecentGroup.lastOpenedAt]. */
    val lastOpenedAt: String? = null,
    /** ISO-8601, or null, see [RecentGroup.updatedAt]. */
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

/**
 * [DefaultSplit] by hand, since `:core`'s [SplitMode] is not `@Serializable`. Stored by enum name
 * and re-resolved with [SplitMode.valueOf], whose throw for a name written by a newer version is
 * deliberately left to propagate: [load] degrades the whole blob rather than guess at a split.
 */
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
