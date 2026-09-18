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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

// A thin DataStore adapter for RecentGroupsSnapshot. "Thin" is load-bearing here, not a figure of
// speech: every rule that decides what the stored list actually *is* — the union merge, which row
// wins a conflict, when a tombstone expires, who a stale participant resolves to — lives in
// `:core`'s RecentGroupsSnapshot and is exercised by RecentGroupsTest on the JVM in milliseconds.
// This class's entire job is turning that snapshot into bytes and back. If a future change adds
// an `if` to this file that decides something rather than merely translating it, that logic has
// drifted out of the place it can actually be tested — put it back in `:core` instead.
//
// ── Why JSON, and why through a hand-written DTO rather than :core's own types ────────────────
//
// This is on-device data nobody can recover if it is written wrong: there is no account and no
// server-side copy (see RecentGroups.kt), so a snapshot this store fails to read back is a list
// of groups gone for the person holding the phone. Two decisions follow from that:
//
//  1. JSON, via kotlinx.serialization, decoded with `ignoreUnknownKeys = true` and every field on
//     [StoredSnapshot]/[StoredGroup] defaulted. That combination is what lets the schema *gain* a
//     field in a later part without breaking an install that already has data on disk: an old
//     field an older APK never wrote decodes to its default, and a newer field a future APK adds
//     is silently dropped by an install that hasn't been updated yet, rather than either side
//     refusing to parse the other's file. A binary format (a Preferences `ByteArray`, a hand
//     rolled struct) can do the same in principle, but only by re-deriving this same forward/
//     backward-compatible framing by hand — JSON with these two settings gets it for free and is
//     also something a person can read off the device if something ever needs debugging by eye.
//
//  2. [StoredSnapshot]/[StoredGroup] are this file's own types, not `@Serializable` versions of
//     `:core`'s [RecentGroupsSnapshot]/[RecentGroup]. `:core` depends on nothing beyond the Kotlin
//     stdlib (see core/build.gradle.kts) precisely so it stays a JVM library with no reason to
//     drag in an Android or serialization dependency; annotating its classes for kotlinx.
//     serialization would puncture that on behalf of one storage format `:core` itself never
//     reads or writes. The conversion functions on the DTOs below are the entire cost of keeping
//     that boundary, and they are the only place this file's few lines of "logic" live — copying
//     values across a field, never deciding anything about them.

/** One process-wide DataStore, opened once per [Context]. */
private val Context.recentGroupsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recent_groups",
)

/** The single key: the whole snapshot is one JSON blob, not one Preferences entry per field. */
private val SNAPSHOT_KEY = stringPreferencesKey("snapshot")

private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

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
        val json = dataStore.data.first()[SNAPSHOT_KEY] ?: return RecentGroupsSnapshot()
        return try {
            // Decoding and converting are one attempt, not two: a row this build cannot make
            // sense of — malformed JSON, or a `SplitMode` name written by a newer version this
            // install has never heard of (see [StoredSplit]) — must degrade the same way either
            // failure happens to surface.
            JSON.decodeFromString(StoredSnapshot.serializer(), json).toCore()
        } catch (_: Exception) {
            // A blob this device itself wrote but can no longer parse — corrupted storage, or a
            // downgrade past a format this build doesn't understand — is worse to crash the app
            // over than to treat as empty. The list rebuilds as groups are opened again; a crash
            // loop on launch would not.
            RecentGroupsSnapshot()
        }
    }

    override suspend fun save(snapshot: RecentGroupsSnapshot) {
        val json = JSON.encodeToString(StoredSnapshot.serializer(), StoredSnapshot.from(snapshot))
        dataStore.edit { prefs -> prefs[SNAPSHOT_KEY] = json }
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
     *  rather than failing to decode — see the note on `ignoreUnknownKeys` above. */
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val participantId: String? = null,
    val defaultSplit: StoredSplit? = null,
    /** ISO-8601, or null — see [RecentGroup.lastOpenedAt]. */
    val lastOpenedAt: String? = null,
    /** ISO-8601, or null — see [RecentGroup.updatedAt]. */
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
 * [SplitMode.valueOf], which throws for a name this build has never heard of — a split written
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
