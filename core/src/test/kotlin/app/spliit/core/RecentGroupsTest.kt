package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

private val T0: Instant = Instant.parse("2025-01-01T00:00:00Z")

private fun group(
    id: String,
    instanceBaseUrl: String = "https://spliit.app",
    name: String = "Group $id",
    participantId: String? = null,
    defaultSplit: DefaultSplit? = null,
    lastOpenedAt: Instant? = null,
    updatedAt: Instant? = null,
): RecentGroup = RecentGroup(
    groupId = id,
    instanceBaseUrl = instanceBaseUrl,
    groupName = name,
    participantId = participantId,
    defaultSplit = defaultSplit,
    lastOpenedAt = lastOpenedAt,
    updatedAt = updatedAt,
)

class RecentGroupsTest {

    // ---- stamping ---------------------------------------------------------------------

    @Test
    fun `opening a new group stamps both updatedAt and lastOpenedAt`() {
        val snapshot = RecentGroupsSnapshot().opening(group("g1"), now = T0)

        val row = snapshot.groups.single()
        assertEquals(T0, row.updatedAt)
        assertEquals(T0, row.lastOpenedAt)
    }

    @Test
    fun `settingParticipantId stamps updatedAt without moving lastOpenedAt`() {
        val opened = RecentGroupsSnapshot().opening(group("g1"), now = T0)
        val later = T0.plusSeconds(60)

        val updated = opened.settingParticipantId("g1", "p1", now = later)

        val row = updated.groups.single()
        assertEquals(later, row.updatedAt, "settingParticipantId must stamp updatedAt")
        assertEquals(T0, row.lastOpenedAt, "answering who-am-I must not touch lastOpenedAt")
        assertEquals("p1", row.participantId)
    }

    @Test
    fun `settingDefaultSplit stamps updatedAt without moving lastOpenedAt`() {
        val opened = RecentGroupsSnapshot().opening(group("g1"), now = T0)
        val later = T0.plusSeconds(60)
        val split = DefaultSplit(splitMode = SplitMode.BY_SHARES, shares = mapOf("p1" to 100L))

        val updated = opened.settingDefaultSplit("g1", split, now = later)

        val row = updated.groups.single()
        assertEquals(later, row.updatedAt, "settingDefaultSplit must stamp updatedAt")
        assertEquals(T0, row.lastOpenedAt, "saving a split must not touch lastOpenedAt")
        assertEquals(split, row.defaultSplit)
    }

    @Test
    fun `settingParticipantId is a no-op for a group the list has never heard of`() {
        val snapshot = RecentGroupsSnapshot()

        val result = snapshot.settingParticipantId("ghost", "p1", now = T0)

        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `opening an existing group refreshes its name but keeps the locally-known participant and split`() {
        val split = DefaultSplit(splitMode = SplitMode.BY_SHARES, shares = mapOf("p1" to 100L))
        val opened = RecentGroupsSnapshot()
            .opening(group("g1", name = "Old Name"), now = T0)
            .settingParticipantId("g1", "p1", now = T0.plusSeconds(1))
            .settingDefaultSplit("g1", split, now = T0.plusSeconds(2))

        // A fresh row as a server response would build it: no participant, no split.
        val reopened = opened.opening(group("g1", name = "New Name"), now = T0.plusSeconds(10))

        val row = reopened.groups.single()
        assertEquals("New Name", row.groupName)
        assertEquals("p1", row.participantId, "the server response carries no participant, but ours must survive")
        assertEquals(split, row.defaultSplit, "the server response carries no split, but ours must survive")
    }

    // ---- ordering -----------------------------------------------------------------------

    @Test
    fun `orderedGroups sorts by lastOpenedAt, most recent first`() {
        var snapshot = RecentGroupsSnapshot()
        snapshot = snapshot.opening(group("g1"), now = T0)
        snapshot = snapshot.opening(group("g2"), now = T0.plusSeconds(10))
        snapshot = snapshot.opening(group("g3"), now = T0.plusSeconds(5))

        assertEquals(listOf("g2", "g3", "g1"), snapshot.orderedGroups.map { it.groupId })
    }

    @Test
    fun `answering who-am-I does not reorder the list`() {
        var snapshot = RecentGroupsSnapshot()
        snapshot = snapshot.opening(group("g1"), now = T0)
        snapshot = snapshot.opening(group("g2"), now = T0.plusSeconds(10))

        // g1 was opened before g2, so g2 leads. Setting g1's participant much later must not
        // move it ahead of g2, only opening does that.
        snapshot = snapshot.settingParticipantId("g1", "p1", now = T0.plusSeconds(100))

        assertEquals(listOf("g2", "g1"), snapshot.orderedGroups.map { it.groupId })
    }

    // ---- starring and archiving -----------------------------------------------------------

    @Test
    fun `settingStarred stamps updatedAt without moving lastOpenedAt`() {
        val opened = RecentGroupsSnapshot().opening(group("g1"), now = T0)
        val later = T0.plusSeconds(60)

        val updated = opened.settingStarred("g1", isStarred = true, now = later)

        val row = updated.groups.single()
        assertTrue(row.isStarred)
        // The stamp is the whole point: starring does not reorder anything, so nothing else on
        // the row says this copy is newer than the one another device is holding.
        assertEquals(later, row.updatedAt, "settingStarred must stamp updatedAt")
        assertEquals(T0, row.lastOpenedAt, "starring must not touch lastOpenedAt")
    }

    @Test
    fun `settingArchived stamps updatedAt without moving lastOpenedAt`() {
        val opened = RecentGroupsSnapshot().opening(group("g1"), now = T0)
        val later = T0.plusSeconds(60)

        val updated = opened.settingArchived("g1", isArchived = true, now = later)

        val row = updated.groups.single()
        assertTrue(row.isArchived)
        assertEquals(later, row.updatedAt, "settingArchived must stamp updatedAt")
        assertEquals(T0, row.lastOpenedAt, "archiving must not touch lastOpenedAt")
    }

    @Test
    fun `starring an archived group brings it out of the archive, and archiving unstars`() {
        var snapshot = RecentGroupsSnapshot().opening(group("g1"), now = T0)

        snapshot = snapshot.settingArchived("g1", isArchived = true, now = T0.plusSeconds(1))
        snapshot = snapshot.settingStarred("g1", isStarred = true, now = T0.plusSeconds(2))
        assertEquals(listOf(true, false), snapshot.groups.single().let { listOf(it.isStarred, it.isArchived) })

        snapshot = snapshot.settingArchived("g1", isArchived = true, now = T0.plusSeconds(3))
        assertEquals(listOf(false, true), snapshot.groups.single().let { listOf(it.isStarred, it.isArchived) })
    }

    @Test
    fun `unstarring and unarchiving leave the other flag alone`() {
        var snapshot = RecentGroupsSnapshot().opening(group("g1"), now = T0)

        snapshot = snapshot.settingStarred("g1", isStarred = true, now = T0.plusSeconds(1))
        snapshot = snapshot.settingStarred("g1", isStarred = false, now = T0.plusSeconds(2))

        val row = snapshot.groups.single()
        assertEquals(false, row.isStarred)
        assertEquals(false, row.isArchived)
    }

    @Test
    fun `settingStarred and settingArchived are no-ops for a group the list has never heard of`() {
        val snapshot = RecentGroupsSnapshot()

        assertEquals(snapshot, snapshot.settingStarred("nope", isStarred = true, now = T0))
        assertEquals(snapshot, snapshot.settingArchived("nope", isArchived = true, now = T0))
    }

    @Test
    fun `the three sections split the list, each group in exactly one`() {
        var snapshot = RecentGroupsSnapshot()
        snapshot = snapshot.opening(group("g1"), now = T0)
        snapshot = snapshot.opening(group("g2"), now = T0.plusSeconds(10))
        snapshot = snapshot.opening(group("g3"), now = T0.plusSeconds(20))
        snapshot = snapshot.settingStarred("g1", isStarred = true, now = T0.plusSeconds(30))
        snapshot = snapshot.settingArchived("g2", isArchived = true, now = T0.plusSeconds(40))

        assertEquals(listOf("g1"), snapshot.starred.map { it.groupId })
        assertEquals(listOf("g3"), snapshot.recent.map { it.groupId })
        assertEquals(listOf("g2"), snapshot.archived.map { it.groupId })
    }

    @Test
    fun `each section keeps the most-recently-opened-first order`() {
        var snapshot = RecentGroupsSnapshot()
        snapshot = snapshot.opening(group("g1"), now = T0)
        snapshot = snapshot.opening(group("g2"), now = T0.plusSeconds(10))
        snapshot = snapshot.settingStarred("g1", isStarred = true, now = T0.plusSeconds(20))
        snapshot = snapshot.settingStarred("g2", isStarred = true, now = T0.plusSeconds(21))

        assertEquals(listOf("g2", "g1"), snapshot.starred.map { it.groupId })
    }

    @Test
    fun `opening a starred group keeps the star`() {
        var snapshot = RecentGroupsSnapshot().opening(group("g1"), now = T0)
        snapshot = snapshot.settingStarred("g1", isStarred = true, now = T0.plusSeconds(1))

        // A server response carries no flags, so a refreshed row would unstar the group if
        // `opening` did not keep what only this phone knows.
        snapshot = snapshot.opening(group("g1", name = "Renamed"), now = T0.plusSeconds(2))

        val row = snapshot.groups.single()
        assertEquals("Renamed", row.groupName)
        assertTrue(row.isStarred, "a rename must not quietly unstar a group")
    }

    @Test
    fun `a merge settles a star by updatedAt like any other edit`() {
        val mine = RecentGroupsSnapshot(
            groups = listOf(group("g1", updatedAt = T0, lastOpenedAt = T0)),
        )
        val theirs = RecentGroupsSnapshot(
            groups = listOf(group("g1", updatedAt = T0.plusSeconds(10), lastOpenedAt = T0).copy(isStarred = true)),
        )

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = T0.plusSeconds(20))

        assertTrue(merged.groups.single().isStarred)
    }

    // ---- merging: union ------------------------------------------------------------------

    @Test
    fun `merging keeps the union of two disjoint lists`() {
        val mine = RecentGroupsSnapshot(groups = listOf(group("g1", updatedAt = T0, lastOpenedAt = T0)))
        val theirs = RecentGroupsSnapshot(groups = listOf(group("g2", updatedAt = T0, lastOpenedAt = T0)))

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = T0)

        assertEquals(setOf("g1", "g2"), merged.groups.map { it.groupId }.toSet())
    }

    @Test
    fun `a group both sides have is settled by the newer updatedAt`() {
        val mine = RecentGroupsSnapshot(
            groups = listOf(group("g1", name = "Mine", updatedAt = T0, lastOpenedAt = T0)),
        )
        val theirs = RecentGroupsSnapshot(
            groups = listOf(
                group("g1", name = "Theirs", updatedAt = T0.plusSeconds(1), lastOpenedAt = T0),
            ),
        )

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = T0)

        assertEquals("Theirs", merged.groups.single().groupName)
    }

    @Test
    fun `the older updatedAt loses even when it is on the mine side`() {
        val mine = RecentGroupsSnapshot(
            groups = listOf(
                group("g1", name = "Mine", updatedAt = T0.plusSeconds(5), lastOpenedAt = T0),
            ),
        )
        val theirs = RecentGroupsSnapshot(
            groups = listOf(group("g1", name = "Theirs", updatedAt = T0, lastOpenedAt = T0)),
        )

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = T0)

        assertEquals("Mine", merged.groups.single().groupName)
    }

    // ---- merging: tombstones --------------------------------------------------------------

    @Test
    fun `forget leaves a tombstone`() {
        val opened = RecentGroupsSnapshot().opening(group("g1"), now = T0)

        val forgotten = opened.forget("g1", now = T0.plusSeconds(1))

        assertTrue(forgotten.groups.isEmpty())
        assertEquals(T0.plusSeconds(1), forgotten.tombstones["g1"])
    }

    @Test
    fun `merging does not resurrect a tombstoned group`() {
        val mine = RecentGroupsSnapshot().opening(group("g1"), now = T0).forget("g1", now = T0.plusSeconds(1))
        // The other phone never heard about the deletion, so it still has the row.
        val theirs = RecentGroupsSnapshot(groups = listOf(group("g1", updatedAt = T0, lastOpenedAt = T0)))

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = T0.plusSeconds(2))

        assertTrue(merged.groups.isEmpty(), "a tombstone newer than the surviving row's updatedAt must win")
        assertEquals(T0.plusSeconds(1), merged.tombstones["g1"])
    }

    @Test
    fun `a tombstone older than 90 days is dropped`() {
        val mine = RecentGroupsSnapshot(
            tombstones = mapOf("g1" to T0),
        )
        val theirs = RecentGroupsSnapshot()
        val ninetyOneDaysLater = T0.plus(Duration.ofDays(91))

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = ninetyOneDaysLater)

        assertTrue(merged.tombstones.isEmpty())
    }

    @Test
    fun `a tombstone exactly at 90 days is dropped, one short of it survives`() {
        val justUnder = RecentGroupsSnapshot.merging(
            RecentGroupsSnapshot(tombstones = mapOf("g1" to T0)),
            RecentGroupsSnapshot(),
            now = T0.plus(Duration.ofDays(90)).minusSeconds(1),
        )
        val atNinety = RecentGroupsSnapshot.merging(
            RecentGroupsSnapshot(tombstones = mapOf("g1" to T0)),
            RecentGroupsSnapshot(),
            now = T0.plus(Duration.ofDays(90)),
        )

        assertTrue(justUnder.tombstones.containsKey("g1"))
        assertTrue(atNinety.tombstones.isEmpty())
    }

    @Test
    fun `a tombstone newer than the other side's row still wins`() {
        val mine = RecentGroupsSnapshot(tombstones = mapOf("g1" to T0.plusSeconds(10)))
        val theirs = RecentGroupsSnapshot(
            groups = listOf(group("g1", updatedAt = T0.plusSeconds(5), lastOpenedAt = T0)),
        )

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = T0.plusSeconds(20))

        assertTrue(merged.groups.isEmpty())
    }

    @Test
    fun `an older tombstone does not win against a row edited after it`() {
        // The other device deleted g1 at T0, but then (from a third device, or undone locally)
        // g1 was opened again at T0+10, an edit after the tombstone is evidence the deletion
        // was undone, so the row must survive.
        val mine = RecentGroupsSnapshot(tombstones = mapOf("g1" to T0))
        val theirs = RecentGroupsSnapshot(
            groups = listOf(group("g1", updatedAt = T0.plusSeconds(10), lastOpenedAt = T0.plusSeconds(10))),
        )

        val merged = RecentGroupsSnapshot.merging(mine, theirs, now = T0.plusSeconds(20))

        assertEquals(1, merged.groups.size)
        assertEquals("g1", merged.groups.single().groupId)
    }

    @Test
    fun `opening a group removes its own tombstone`() {
        val forgotten = RecentGroupsSnapshot().opening(group("g1"), now = T0).forget("g1", now = T0.plusSeconds(1))

        val reopened = forgotten.opening(group("g1"), now = T0.plusSeconds(2))

        assertTrue(reopened.tombstones.isEmpty())
        assertEquals(1, reopened.groups.size)
    }

    // ---- actorId ----------------------------------------------------------------------

    @Test
    fun `actorId resolves the remembered participant`() {
        val snapshot = RecentGroupsSnapshot()
            .opening(group("g1"), now = T0)
            .settingParticipantId("g1", "p1", now = T0)

        val participants = listOf(Participant("p1", "Ana"), Participant("p2", "Bo"))

        assertEquals("p1", snapshot.actorId("g1", participants))
    }

    @Test
    fun `actorId returns null when the remembered participant is no longer in the group`() {
        val snapshot = RecentGroupsSnapshot()
            .opening(group("g1"), now = T0)
            .settingParticipantId("g1", "gone", now = T0)

        val participants = listOf(Participant("p1", "Ana"), Participant("p2", "Bo"))

        assertNull(snapshot.actorId("g1", participants))
    }

    @Test
    fun `actorId returns null for a group never answered for`() {
        val snapshot = RecentGroupsSnapshot().opening(group("g1"), now = T0)

        assertNull(snapshot.actorId("g1", listOf(Participant("p1", "Ana"))))
    }

    @Test
    fun `actorId returns null for a group the list has never heard of`() {
        assertNull(RecentGroupsSnapshot().actorId("ghost", listOf(Participant("p1", "Ana"))))
    }
}
