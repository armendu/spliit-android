package app.spliit.core

import java.time.Duration
import java.time.Instant

// The local list of groups this phone can get back to, and the merge that lets two phones share
// one list without a server that knows about either of them.
//
// Spliit has no accounts. A group is reachable only by its ID, and this list is the only record
// of the IDs this phone has seen. A row this merge drops is a group gone for good, which is what
// the four rules below are for.
//
//  1. Merging keeps the union. Two phones with different lists have no obvious loser, so a row
//     either side has survives.
//  2. A row both sides have is resolved by [RecentGroup.updatedAt], so every mutation stamps it,
//     including the ones that do not reorder the list. A change that left it alone is one the
//     other phone would silently discard.
//  3. Ordering is a separate question, answered by [RecentGroup.lastOpenedAt], which only
//     [opening] touches. Renaming a group must not jump it to the top.
//  4. A union cannot express a deletion, so [forget] leaves a tombstone that [merging] checks.
//     Tombstones expire after [RecentGroupsSnapshot.TOMBSTONE_LIFETIME]; kept forever, the store
//     nobody deletes from would fill with the other one's graveyard.
/**
 * A group this phone has opened, remembered locally so it can be reached again without a server
 * that keeps a list on our behalf.
 */
public data class RecentGroup(
    public val groupId: String,
    /**
     * Where this group lives. A group ID alone answers nothing: one list can hold groups on
     * spliit.app and on a self-hosted server, and the wrong one returns "not found".
     *
     * A [String], not [java.net.URL], whose `equals` resolves hostnames over the network.
     */
    public val instanceBaseUrl: String,
    /** This group's name, as of the last time this phone saw it. */
    public val groupName: String,
    /** Kept at the top of the list. On the row rather than in a separate set, so a forgotten
     *  group cannot be left starred. */
    public val isStarred: Boolean = false,
    /** Kept but out of the way. Never set at the same time as [isStarred]. */
    public val isArchived: Boolean = false,
    /** Who this phone is here, or null if never answered. Read via
     *  [RecentGroupsSnapshot.actorId], which drops a participant who has left. */
    public val participantId: String? = null,
    /** How this group's expenses are usually divided, once [settingDefaultSplit] has set it. */
    public val defaultSplit: DefaultSplit? = null,
    /** What "most recently used" sorts by. Only [opening] moves it. */
    public val lastOpenedAt: Instant? = null,
    /** When this row last changed, in any way. What [RecentGroupsSnapshot.merging] settles a
     *  conflict with, so every mutation stamps it. */
    public val updatedAt: Instant? = null,
)

/** What one device knows: the groups, and the ones it has been told to forget. An empty
 *  snapshot is what a device that has never deleted anything carries. */
public data class RecentGroupsSnapshot(
    public val groups: List<RecentGroup> = emptyList(),
    /** Group ID to when it was deleted. See [forget] and [merging]. */
    public val tombstones: Map<String, Instant> = emptyMap(),
) {

    /**
     * [groups], most recently opened first. Computed on read so no mutation has to remember to
     * re-sort. The group ID breaks ties, so two devices cannot disagree about the order.
     */
    public val orderedGroups: List<RecentGroup>
        get() = groups.sortedWith(
            compareByDescending<RecentGroup> { it.lastOpenedAt ?: Instant.MIN }.thenBy { it.groupId },
        )

    // The home screen's three sections. Every group is in exactly one, and a row carrying both
    // flags reads as archived, the quieter mistake. Derived from [orderedGroups] so each keeps
    // the same ordering.

    /** Starred and not archived, pinned to the top of the list. */
    public val starred: List<RecentGroup>
        get() = orderedGroups.filter { it.isStarred && !it.isArchived }

    /** Neither starred nor archived: the ordinary list, most recently opened first. */
    public val recent: List<RecentGroup>
        get() = orderedGroups.filter { !it.isStarred && !it.isArchived }

    /** Archived, starred or not. */
    public val archived: List<RecentGroup>
        get() = orderedGroups.filter { it.isArchived }

    /**
     * Adds [group], or moves it to the front, keeping what only this phone knows: [group] comes
     * from the server with no participant answer, split or flags, so the existing row's win.
     * Without that, opening a starred group would unstar it. Any tombstone is removed.
     */
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

    /** Records who this phone is in [groupId]. A no-op for an unknown group, and does not touch
     *  [RecentGroup.lastOpenedAt]: answering this is not opening the group. */
    public fun settingParticipantId(
        groupId: String,
        participantId: String?,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) { it.copy(participantId = participantId) }

    /** Remembers how [groupId]'s expenses divide. As [settingParticipantId]: no-op for an
     *  unknown group, silent on [RecentGroup.lastOpenedAt]. */
    public fun settingDefaultSplit(
        groupId: String,
        defaultSplit: DefaultSplit,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) { it.copy(defaultSplit = defaultSplit) }

    /**
     * Stars [groupId] or takes the star away; starring un-archives. Stamps [RecentGroup.updatedAt]
     * even though nothing reorders: an unstamped star is one the next merge may discard.
     */
    public fun settingStarred(
        groupId: String,
        isStarred: Boolean,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) {
        it.copy(isStarred = isStarred, isArchived = if (isStarred) false else it.isArchived)
    }

    /** Archives [groupId], or brings it back. Archiving unstars, for [settingStarred]'s reason. */
    public fun settingArchived(
        groupId: String,
        isArchived: Boolean,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) {
        it.copy(isArchived = isArchived, isStarred = if (isArchived) false else it.isStarred)
    }

    /**
     * Applies [change] to [groupId] and stamps [RecentGroup.updatedAt]. The one place every
     * non-[opening] edit goes through, so no mutation has to remember to stamp. No-op for an
     * unknown group.
     */
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

    /** Removes [groupId] and leaves a tombstone. Without one, merging with a device that has
     *  not heard about the deletion puts the group straight back. */
    public fun forget(groupId: String, now: Instant = Instant.now()): RecentGroupsSnapshot =
        copy(
            groups = groups.filterNot { it.groupId == groupId },
            tombstones = tombstones + (groupId to now),
        )

    /**
     * Who to credit for a write from this phone. Null when unanswered, and also when the
     * remembered participant has left the group, which is why call sites use this instead of
     * [RecentGroup.participantId]. The log then reads "Someone".
     */
    public fun actorId(groupId: String, participants: List<Participant>): String? {
        val remembered = groups.firstOrNull { it.groupId == groupId }?.participantId ?: return null
        return remembered.takeIf { id -> participants.any { it.id == id } }
    }

    public companion object {
        /** Long enough for a phone left in a drawer to still have its deletions honoured,
         *  short enough that tombstones do not outweigh the groups. */
        public val TOMBSTONE_LIFETIME: Duration = Duration.ofDays(90)

        /**
         * Combines what two devices know, giving the same answer whichever one is asking:
         *
         *  1. A row either side has is kept: a union, not a replace.
         *  2. A row both sides have is taken whole from the newer [RecentGroup.updatedAt], never
         *     field by field, since a row's fields were last edited together.
         *  3. A tombstone newer than the surviving row removes it; one the row's own edit
         *     postdates does not, that edit being evidence the deletion was undone.
         *  4. Tombstones older than [TOMBSTONE_LIFETIME] are dropped before they can act.
         *  5. Ordering is not merged, it is recomputed from the surviving [lastOpenedAt]s.
         */
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
