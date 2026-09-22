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
// the stored list *is* (the union merge, which row wins, when a tombstone expires) lives in
// `:core` and is tested there on the JVM. This class turns a snapshot into bytes and back. An
// `if` here that decides something rather than translating it belongs in `:core`.
//
// StoredJson via kotlinx.serialization, with `ignoreUnknownKeys = true` and every DTO field defaulted.
// That pair is what lets the schema gain a field without breaking an install that already has
// data: an older file decodes missing fields to defaults, and a newer file's extra fields are
// dropped by an older APK rather than failing to parse. This is unrecoverable data, there is no
// server copy, so surviving a version skew matters more than compactness. It is also readable
// off the device by eye.
//
// The DTOs are this file's own types rather than `@Serializable` versions of `:core`'s, because
// `:core` depends on nothing beyond the stdlib and should not gain a serialization dependency
// for one storage format it never reads. The conversion functions below are the whole cost of
// that boundary.

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
        // The read itself, not only the decode. DataStore documents `data` as throwing
        // IOException, and this runs inside `viewModelScope.launch` at call sites with no
        // exception handler, where a throw reaches the thread's default handler and takes the
        // process with it. An unreadable file degrades to an empty list, exactly as a
        // corrupt one already did below.
        val json = try {
            dataStore.data.first()[SNAPSHOT_KEY]
        } catch (_: IOException) {
            return RecentGroupsSnapshot()
        } ?: return RecentGroupsSnapshot()
        return try {
            // Decoding and converting are one attempt, not two: a row this build cannot make
            // sense of, malformed StoredJson, or a `SplitMode` name written by a newer version this
            // install has never heard of (see [StoredSplit]), must degrade the same way either
            // failure happens to surface.
            StoredJson.decodeFromString(StoredSnapshot.serializer(), json).toCore()
        } catch (_: Exception) {
            // A blob this device itself wrote but can no longer parse, corrupted storage, or a
            // downgrade past a format this build doesn't understand, is worse to crash the app
            // over than to treat as empty. The list rebuilds as groups are opened again; a crash
            // loop on launch would not.
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
 * [DefaultSplit] by hand: `:core`'s [SplitMode] carries no `@Serializable` annotation (see the
 * note at the top of this file), so it is stored by its enum name and re-resolved with
 * [SplitMode.valueOf], which throws for a name this build has never heard of, a split written
 * by a newer version, read on an install that hasn't caught up. That throw is deliberately left
 * to propagate out of [toCore] rather than defaulted to some mode nobody chose: [load] treats the
 * whole blob as one attempt and degrades the entire snapshot to empty rather than guess at a
 * split it cannot actually represent.
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
