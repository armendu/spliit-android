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
     * Where this group lives, as a base URL, `spliit.app`, or a self-hosted instance's own
     * address. A group ID alone answers nothing: the same list can hold a flatshare on spliit.app
     * and a family group on a server in somebody's hallway, and asking the wrong one about either
     * gets back "not found" rather than the group. A plain [String] rather than [java.net.URL] -
     * `URL.equals` resolves hostnames to compare them, which is a network call this comparison
     * should never make.
     */
    public val instanceBaseUrl: String,
    /** This group's name, as of the last time this phone saw it. */
    public val groupName: String,
    /**
     * Kept at the top of the list. The web app stores its starred IDs in a second `localStorage`
     * key because that is what `localStorage` gives you; here the flag belongs to the row, which
     * means one write and no way to end up starring a group the list has already forgotten.
     */
    public val isStarred: Boolean = false,
    /**
     * Kept, but out of the way, the trip that ended, the flatshare somebody moved out of.
     * Starring and archiving say opposite things, so [RecentGroupsSnapshot.settingStarred] and
     * [RecentGroupsSnapshot.settingArchived] never let a row carry both.
     */
    public val isArchived: Boolean = false,
    /**
     * Who this phone is in this group, a participant ID, or null when that has never been
     * answered. Read this through [RecentGroupsSnapshot.actorId], not directly: a participant who
     * has since left the group must never be handed back as though they still belonged to it.
     */
    public val participantId: String? = null,
    /** How this group's expenses are usually divided, once [settingDefaultSplit] has set it. */
    public val defaultSplit: DefaultSplit? = null,
    /**
     * When this group was last opened, what "most recently used" sorts by, via
     * [RecentGroupsSnapshot.orderedGroups]. The one field [opening] moves and every other
     * mutation on this row deliberately leaves alone; see the note at the top of this file.
     */
    public val lastOpenedAt: Instant? = null,
    /**
     * When this row last changed, in any way. The one field [RecentGroupsSnapshot.merging] trusts
     * to settle a group both sides have, see the note at the top of this file for why every
     * mutation stamps it, including the ones that do not move [lastOpenedAt].
     */
    public val updatedAt: Instant? = null,
)

/**
 * The whole of what one device knows about the list: the groups, and the ones it has been told to
 * forget.
 *
 * The tombstones live here, and only here. A snapshot with no tombstones at all is not a
 * malformed one, the empty snapshot [RecentGroupsSnapshot()] is exactly what a device that has
 * never deleted anything, or that has not yet synced, carries.
 */
public data class RecentGroupsSnapshot(
    public val groups: List<RecentGroup> = emptyList(),
    /** Group ID to when it was deleted. See [forget] and [merging]. */
    public val tombstones: Map<String, Instant> = emptyMap(),
) {

    /**
     * [groups], most recently opened first.
     *
     * Computed on every read rather than maintained as the list's own order, so no mutation below
     * has to remember to re-sort, there is exactly one place ordering can go wrong instead of
     * one per mutation. The group ID breaks a tie so that two devices sorting the same rows, at
     * the same [RecentGroup.lastOpenedAt] down to the tick, cannot disagree about the order.
     */
    public val orderedGroups: List<RecentGroup>
        get() = groups.sortedWith(
            compareByDescending<RecentGroup> { it.lastOpenedAt ?: Instant.MIN }.thenBy { it.groupId },
        )

    // The three collections the home screen draws as its three sections, in the order it draws
    // them. Every group is in exactly one: a row that somehow carried both flags reads as
    // archived, because putting a group away when it should have been starred is the quieter of
    // the two mistakes to make on somebody's behalf. Derived from [orderedGroups] rather than
    // from [groups], so each section keeps the one ordering this type has.

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
     * Adds [group], or moves it to the front and refreshes what a server just told us, while
     * keeping what only this phone knows.
     *
     * [group] is built fresh from a server response, which carries no participant answer, no
     * remembered split and neither flag, so the existing row's [RecentGroup.participantId],
     * [RecentGroup.defaultSplit], [RecentGroup.isStarred] and [RecentGroup.isArchived] win over
     * [group]'s. Anything [group] does *not* carry that this phone has locally would otherwise be
     * destroyed by every rename: opening a starred group would quietly unstar it. Anything else
     * that comes to live on a row belongs in this list too.
     *
     * Removes any tombstone for this group: opening it is the plainest possible statement that it
     * belongs in the list, and it outranks another device having deleted it earlier, see
     * [merging], which would otherwise be free to still honour that tombstone on the next sync.
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

    /**
     * Records who this phone is in [groupId].
     *
     * A no-op for a group this list has never heard of: every way into a group remembers it via
     * [opening] first, so there is no row here to invent one for. Deliberately does **not** touch
     * [RecentGroup.lastOpenedAt], answering "who am I here" is not opening the group, and a list
     * that jumped to the front over it would be a surprise nobody asked for.
     */
    public fun settingParticipantId(
        groupId: String,
        participantId: String?,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) { it.copy(participantId = participantId) }

    /**
     * Remembers how [groupId]'s expenses are usually divided. As [settingParticipantId], a no-op
     * for an unknown group and silent on [RecentGroup.lastOpenedAt], saving a split is not
     * opening the group either.
     */
    public fun settingDefaultSplit(
        groupId: String,
        defaultSplit: DefaultSplit,
        now: Instant = Instant.now(),
    ): RecentGroupsSnapshot = modifying(groupId, now) { it.copy(defaultSplit = defaultSplit) }

    /**
     * Stars [groupId], or takes the star away. Starring un-archives: a group cannot be both the
     * one you reach for most and the one you have put away.
     *
     * As [settingParticipantId], a no-op for an unknown group and silent on
     * [RecentGroup.lastOpenedAt], starring is not opening. It does stamp
     * [RecentGroup.updatedAt], though the list does not reorder: that stamp is the *only* thing
     * another device compares against, and a star this snapshot left unstamped is a star the
     * next merge is free to discard without anything looking wrong.
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
     * Applies [change] to [groupId] and stamps [RecentGroup.updatedAt].
     *
     * The one place every non-[opening] edit goes through, so that stamping is not something each
     * mutation has to remember, the note at the top of this file is about a field that is lost
     * silently when it is forgotten, which is exactly the kind of thing a per-call-site
     * responsibility eventually forgets. A group this list has never heard of is a no-op: every
     * way into a group remembers it via [opening] first, so there is no row here to invent one
     * for.
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

    /**
     * Removes [groupId] and leaves a tombstone in its place.
     *
     * The tombstone is the one thing a plain union cannot express, see rule 4 at the top of this
     * file. Without it, merging with a device that has not heard about this deletion yet puts the
     * group straight back the moment the two lists meet.
     */
    public fun forget(groupId: String, now: Instant = Instant.now()): RecentGroupsSnapshot =
        copy(
            groups = groups.filterNot { it.groupId == groupId },
            tombstones = tombstones + (groupId to now),
        )

    /**
     * Who to credit for a write made from this phone, as the server's activity log wants it.
     *
     * Null twice over: for a group this phone has never answered for, and for a remembered
     * [RecentGroup.participantId] naming somebody [participants] no longer has. The second case is
     * the one that matters, a write must never claim to be someone who has left the group, and
     * it is why this exists instead of every call site reading [RecentGroup.participantId]
     * directly. The log then reads "Someone", which is exactly what is known.
     */
    public fun actorId(groupId: String, participants: List<Participant>): String? {
        val remembered = groups.firstOrNull { it.groupId == groupId }?.participantId ?: return null
        return remembered.takeIf { id -> participants.any { it.id == id } }
    }

    public companion object {
        /**
         * Long enough for a phone that spent a season in a drawer to still have its deletions
         * honoured; short enough that the list of groups somebody once deleted does not become
         * the larger half of what a device stores.
         */
        public val TOMBSTONE_LIFETIME: Duration = Duration.ofDays(90)

        /**
         * Combines what two devices know, in a way that gives the same answer whichever one is
         * asking, so two phones that have both seen both snapshots agree without another round
         * trip.
         *
         * The rules, in the order they matter, see the note at the top of this file for why each
         * one is what it is:
         *
         *  1. A row either side has is kept: this is a union, not a replace.
         *  2. A group both sides have is taken whole from whichever [RecentGroup.updatedAt] is
         *     newer, never field by field, since the fields on a row were last edited together.
         *  3. A tombstone newer than a surviving row's [RecentGroup.updatedAt] removes it; a
         *     tombstone the row's own edit postdates does not, the edit is evidence the deletion
         *     was undone (via [opening]) on the device that made it.
         *  4. Tombstones older than [TOMBSTONE_LIFETIME] are dropped before they can act.
         *  5. Ordering is not merged at all, it is [orderedGroups], computed fresh from whatever
         *     [RecentGroup.lastOpenedAt] survives.
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
