package app.spliit.core

import java.time.Duration
import java.time.Instant

// No accounts: this list is the only record of the groups this phone can reach. Merge rules:
//  1. Merging keeps the union.
//  2. A row both sides have is taken whole from the newer updatedAt, so every mutation stamps it.
//  3. Order comes from lastOpenedAt, which only opening() touches.
//  4. forget() leaves a tombstone so a merge doesn't bring the group back; tombstones expire.
public data class RecentGroup(
    public val groupId: String,
    // A String, not java.net.URL, whose equals() resolves hostnames over the network.
    public val instanceBaseUrl: String,
    public val groupName: String,
    public val isStarred: Boolean = false,
    public val isArchived: Boolean = false,
    public val participantId: String? = null,
    public val defaultSplit: DefaultSplit? = null,
    public val lastOpenedAt: Instant? = null,
    public val updatedAt: Instant? = null,
)

public data class RecentGroupsSnapshot(
    public val groups: List<RecentGroup> = emptyList(),
    public val tombstones: Map<String, Instant> = emptyMap(),
) {
    public val orderedGroups: List<RecentGroup>
        get() = groups.sortedWith(
            compareByDescending<RecentGroup> { it.lastOpenedAt ?: Instant.MIN }.thenBy { it.groupId },
        )

    public val starred: List<RecentGroup>
        get() = orderedGroups.filter { it.isStarred && !it.isArchived }

    public val recent: List<RecentGroup>
        get() = orderedGroups.filter { !it.isStarred && !it.isArchived }

    public val archived: List<RecentGroup>
        get() = orderedGroups.filter { it.isArchived }

    public fun opening(group: RecentGroup, now: Instant = Instant.now()): RecentGroupsSnapshot {
        val existing = groups.firstOrNull { it.groupId == group.groupId }
        val remembered = group.copy(
            participantId = existing?.participantId,
            defaultSplit = existing?.defaultSplit,
            isStarred = existing?.isStarred ?: false,
            isArchived = existing?.isArchived ?: false,
            updatedAt = now,
            lastOpenedAt = now,
        )
        return copy(
            groups = groups.filterNot { it.groupId == group.groupId } + remembered,
            tombstones = tombstones - group.groupId,
        )
    }

    public fun settingParticipantId(
        groupId: String,
        participantId: String?,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) { it.copy(participantId = participantId) }

    public fun settingDefaultSplit(
        groupId: String,
        defaultSplit: DefaultSplit,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) { it.copy(defaultSplit = defaultSplit) }

    public fun settingStarred(
        groupId: String,
        isStarred: Boolean,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) {
        it.copy(isStarred = isStarred, isArchived = if (isStarred) false else it.isArchived)
    }

    public fun settingArchived(
        groupId: String,
        isArchived: Boolean,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) {
        it.copy(isArchived = isArchived, isStarred = if (isArchived) false else it.isStarred)
    }

    private fun modifying(
        groupId: String,
        now: Instant,
        change: (RecentGroup) -> RecentGroup,
    ): RecentGroupsSnapshot {
        val index = groups.indexOfFirst { it.groupId == groupId }
        if (index < 0) return this
        val updated = groups.toMutableList()
        updated[index] = change(updated[index]).copy(updatedAt = now)
        return copy(groups = updated)
    }

    public fun forget(groupId: String, now: Instant = Instant.now()): RecentGroupsSnapshot =
        copy(
            groups = groups.filterNot { it.groupId == groupId },
            tombstones = tombstones + (groupId to now),
        )

    // Use this, not participantId: it drops a participant who has left.
    public fun actorId(groupId: String, participants: List<Participant>): String? {
        val remembered = groups.firstOrNull { it.groupId == groupId }?.participantId ?: return null
        return remembered.takeIf { id -> participants.any { it.id == id } }
    }

    public companion object {
        public val TOMBSTONE_LIFETIME: Duration = Duration.ofDays(90)

        public fun merging(
            mine: RecentGroupsSnapshot,
            theirs: RecentGroupsSnapshot,
            now: Instant = Instant.now(),
        ): RecentGroupsSnapshot {
            val tombstones = HashMap(mine.tombstones)
            for ((groupId, deletedAt) in theirs.tombstones) {
                val current = tombstones[groupId]
                if (current == null || deletedAt.isAfter(current)) tombstones[groupId] = deletedAt
            }
            val liveTombstones = tombstones.filterValues { Duration.between(it, now) < TOMBSTONE_LIFETIME }

            val byId = LinkedHashMap<String, RecentGroup>()
            for (group in mine.groups + theirs.groups) {
                val rival = byId[group.groupId]
                val isNewer = (group.updatedAt ?: Instant.MIN) > (rival?.updatedAt ?: Instant.MIN)
                if (rival == null || isNewer) byId[group.groupId] = group
            }

            val surviving = byId.values.filter { group ->
                val tombstone = liveTombstones[group.groupId] ?: return@filter true
                (group.updatedAt ?: Instant.MIN) > tombstone
            }

            return RecentGroupsSnapshot(groups = surviving.toList(), tombstones = liveTombstones)
        }
    }
}
