package app.spliit.android.feature.group

import app.spliit.api.Activity
import app.spliit.api.ActivityType
import app.spliit.api.ExpenseListItem
import app.spliit.api.Participant
import app.spliit.api.SplitMode
import app.spliit.core.DateBucket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The pure functions behind the group screen's new surfaces — the share link, the activity log's
 * prose and bucketing, and the expense row's two metadata lines. None of them needs a server, a
 * `Context` or a composition, which is why they are plain functions in the first place.
 */
class GroupDetailPresentationTest {

    // ---- the share link ------------------------------------------------------------------------

    @Test
    fun `the share link is the web app's own, on the group's instance`() {
        assertEquals(
            "https://spliit.app/groups/abc123",
            groupShareLink("https://spliit.app", "abc123"),
        )
    }

    @Test
    fun `a stored base URL with a trailing slash does not double it`() {
        // Rows are written by several paths — a pasted link, the create form, a seeded fixture —
        // and they do not agree about the trailing slash.
        assertEquals(
            "https://spliit.example.com/groups/abc123",
            groupShareLink("https://spliit.example.com/", "abc123"),
        )
    }

    @Test
    fun `a self-hosted group is shared on its own server, not on spliit app`() {
        // The bug this exists to prevent: a self-hosted group shared as a spliit.app link opens
        // nothing at all for whoever receives it.
        assertTrue(groupShareLink("http://10.0.2.2:3009/", "g1").startsWith("http://10.0.2.2:3009/"))
    }

    // ---- the activity log ----------------------------------------------------------------------

    private fun activity(
        id: String,
        type: ActivityType,
        at: String,
        title: String? = null,
        participantId: String? = null,
    ) = Activity(
        id = id,
        groupId = "g1",
        time = Instant.parse(at),
        activityType = type,
        participantId = participantId,
        expenseId = "e1",
        title = title,
    )

    @Test
    fun `a change nobody claimed reads as Someone rather than as nobody at all`() {
        // Every mutating procedure takes an optional participantId and none requires one, so this
        // is the honest sentence for a write made by a client that never said who it was.
        val entry = activity("a1", ActivityType.CreateExpense, "2025-06-02T10:00:00Z", title = "Taxi")

        assertEquals("Someone created \"Taxi\"", summaryOf(entry, participantName = null))
        assertEquals("Ana created \"Taxi\"", summaryOf(entry, participantName = "Ana"))
    }

    @Test
    fun `a deleted expense keeps the title it had when it was deleted`() {
        val entry = activity("a1", ActivityType.DeleteExpense, "2025-06-02T10:00:00Z", title = "Fado tickets")

        assertEquals("Ana deleted \"Fado tickets\"", summaryOf(entry, "Ana"))
    }

    @Test
    fun `a group change has no expense title to name`() {
        val entry = activity("a1", ActivityType.UpdateGroup, "2025-06-02T10:00:00Z")

        assertEquals("Ana changed the group settings", summaryOf(entry, "Ana"))
    }

    @Test
    fun `a kind this version cannot describe is dropped rather than drawn`() {
        // There is no honest sentence to put on the row. A log missing a line it cannot describe
        // still reads correctly; one saying "something happened" does not.
        val clock = Clock.fixed(Instant.parse("2025-06-02T12:00:00Z"), ZoneId.of("UTC"))
        val sections = bucketActivities(
            listOf(
                activity("a1", ActivityType.CreateExpense, "2025-06-02T10:00:00Z", title = "Taxi"),
                activity("a2", ActivityType.Unknown("SOMETHING_NEW"), "2025-06-02T11:00:00Z"),
            ),
            clock,
        )

        assertEquals(listOf("a1"), sections.flatMap { it.activities }.map { it.id })
    }

    @Test
    fun `activities fall into the same date buckets the expense list uses, newest first`() {
        val clock = Clock.fixed(Instant.parse("2025-06-02T12:00:00Z"), ZoneId.of("UTC"))
        val sections = bucketActivities(
            listOf(
                activity("today", ActivityType.CreateExpense, "2025-06-02T10:00:00Z", title = "Taxi"),
                activity("yesterday", ActivityType.UpdateExpense, "2025-06-01T10:00:00Z", title = "Dinner"),
                activity("older", ActivityType.UpdateGroup, "2019-01-01T10:00:00Z"),
            ),
            clock,
        )

        assertEquals(
            listOf(DateBucket.TODAY, DateBucket.YESTERDAY, DateBucket.OLDER),
            sections.map { it.bucket },
        )
    }

    // ---- the expense row's two lines -----------------------------------------------------------

    private fun expense(payer: String, vararg payees: String): ExpenseListItem = ExpenseListItem(
        "e1",
        "Taxi",
        1000,
        Instant.parse("2025-06-02T00:00:00Z"),
        Instant.parse("2025-06-02T00:00:00Z"),
        false,
        SplitMode.EVENLY,
        null,
        null,
        Participant("p0", payer),
        payees.mapIndexed { index, name ->
            ExpenseListItem.PaidFor(Participant("p${index + 1}", name), 100)
        },
        ExpenseListItem.Counts(0),
    )

    @Test
    fun `the split line lists the payees and nothing else`() {
        // The "Paid by …" half is its own line now, so this returns only the list.
        assertEquals("Bruno and Chloé", paidForDescription(expense("Ana", "Bruno", "Chloé")))
        assertEquals("Ana, Bruno and Chloé", paidForDescription(expense("Ana", "Ana", "Bruno", "Chloé")))
        assertEquals("Bruno", paidForDescription(expense("Ana", "Bruno")))
    }

    @Test
    fun `an expense the server says covers nobody has no second line`() {
        assertNull(paidForDescription(expense("Ana")))
    }

    @Test
    fun `a screen reader still hears the two lines as one sentence`() {
        // Two Texts in a column are two fragments; the merged node puts the sentence back.
        assertEquals(
            "Paid by Ana for Bruno and Chloé",
            accessibleSplitDescription(expense("Ana", "Bruno", "Chloé")),
        )
        assertEquals("Paid by Ana", accessibleSplitDescription(expense("Ana")))
    }
}
