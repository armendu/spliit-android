package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale

/**
 * Explicit, as in [ExpenseFormDraftTest]: a fractional share's text has a decimal separator, and
 * the machine's default locale is never consulted in this file.
 */
private val AMERICAN = Locale.of("en", "US")

private val THE_DATE: Instant = Instant.parse("2025-03-04T18:30:00Z")

private fun members(count: Int): List<Participant> =
    (1..count).map { Participant("p$it", "Participant $it") }

/** A validated payload, as [ExpenseFormDraft.submission] would hand one to a store. */
private fun submission(
    splitMode: SplitMode,
    paidFor: List<ExpenseSubmission.PaidFor>,
): ExpenseSubmission = ExpenseSubmission(
    title = "Airport taxi",
    expenseDate = THE_DATE,
    amount = 10_000L,
    category = 0,
    paidBy = "p1",
    paidFor = paidFor,
    splitMode = splitMode,
    saveDefaultSplittingOptions = true,
    isReimbursement = false,
    notes = null,
    recurrenceRule = ExpenseFormDraft.NO_RECURRENCE,
    originalCurrency = null,
    originalAmount = null,
    conversionRate = null,
)

class DefaultSplitTest {

    // ---- what gets remembered -----------------------------------------------------------

    /**
     * `BY_AMOUNT`'s shares are one receipt's own amounts, the €12.40 somebody owed on
     * Tuesday's dinner means nothing about Wednesday's. Only the mode survives.
     */
    @Test
    fun `BY_AMOUNT keeps only its mode`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.BY_AMOUNT,
                listOf(
                    ExpenseSubmission.PaidFor("p1", 5000),
                    ExpenseSubmission.PaidFor("p2", 3000),
                    ExpenseSubmission.PaidFor("p3", 2000),
                ),
            ),
            members(3),
        )

        assertEquals(SplitMode.BY_AMOUNT, split.splitMode)
        assertNull(split.shares)
    }

    /**
     * An even split naming everyone remembers the *group*, not the three names, so someone who
     * joins later is still swept in. Nothing else in the split can do this: 50/30/20 breaks the
     * moment a fourth name is added to it.
     */
    @Test
    fun `an even split of the whole group is stored as membership alone`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.EVENLY,
                listOf(
                    ExpenseSubmission.PaidFor("p1", 100),
                    ExpenseSubmission.PaidFor("p2", 100),
                    ExpenseSubmission.PaidFor("p3", 100),
                ),
            ),
            members(3),
        )

        assertEquals(SplitMode.EVENLY, split.splitMode)
        assertNull(split.shares)
    }

    @Test
    fun `a participant who joins later is included in a whole-group even split`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.EVENLY,
                listOf(
                    ExpenseSubmission.PaidFor("p1", 100),
                    ExpenseSubmission.PaidFor("p2", 100),
                    ExpenseSubmission.PaidFor("p3", 100),
                ),
            ),
            members(3),
        )

        val seeded = split.apply(members(4)) // p4 is the newcomer, unknown to the split.
        assertEquals(listOf("p1", "p2", "p3", "p4"), seeded.filter { it.isIncluded }.map { it.id })
    }

    /** An even split of a *subset* is a real choice about who is in it, not a shortcut for "all". */
    @Test
    fun `an even split of a subset keeps its names`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.EVENLY,
                listOf(ExpenseSubmission.PaidFor("p1", 100), ExpenseSubmission.PaidFor("p2", 100)),
            ),
            members(3),
        )

        assertEquals(mapOf("p1" to 100L, "p2" to 100L), split.shares)
    }

    @Test
    fun `a participant who joins later is not included in a subset even split`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.EVENLY,
                listOf(ExpenseSubmission.PaidFor("p1", 100), ExpenseSubmission.PaidFor("p2", 100)),
            ),
            members(3),
        )

        val seeded = split.apply(members(4))
        assertEquals(listOf("p1", "p2"), seeded.filter { it.isIncluded }.map { it.id })
    }

    /**
     * Under `BY_SHARES` and `BY_PERCENTAGE` the numbers mean something specific to the people
     * named, nobody joins a 50/30/20 split without breaking it, so these always keep their
     * names, even when today's split happens to cover the whole group.
     */
    @Test
    fun `a BY_PERCENTAGE split of the whole group still keeps its names`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.BY_PERCENTAGE,
                listOf(
                    ExpenseSubmission.PaidFor("p1", 5000),
                    ExpenseSubmission.PaidFor("p2", 3000),
                    ExpenseSubmission.PaidFor("p3", 2000),
                ),
            ),
            members(3),
        )

        assertEquals(mapOf("p1" to 5000L, "p2" to 3000L, "p3" to 2000L), split.shares)
    }

    @Test
    fun `a newcomer is excluded from a BY_SHARES split even when it covered everyone`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.BY_SHARES,
                listOf(
                    ExpenseSubmission.PaidFor("p1", 100),
                    ExpenseSubmission.PaidFor("p2", 100),
                    ExpenseSubmission.PaidFor("p3", 100),
                ),
            ),
            members(3),
        )

        val seeded = split.apply(members(4))
        assertEquals(listOf("p1", "p2", "p3"), seeded.filter { it.isIncluded }.map { it.id })
    }

    /**
     * Same rule as [BY_SHARES][SplitMode.BY_SHARES] above, run through [SplitMode.BY_PERCENTAGE]
     * too. The two share the same [DefaultSplit.apply] code path, so this is low-risk, but the
     * rule it is checking is the fragile one in this file, and symmetry costs one test.
     */
    @Test
    fun `a newcomer is excluded from a BY_PERCENTAGE split even when it covered everyone`() {
        val split = DefaultSplit.remembering(
            submission(
                SplitMode.BY_PERCENTAGE,
                listOf(
                    ExpenseSubmission.PaidFor("p1", 5000),
                    ExpenseSubmission.PaidFor("p2", 3000),
                    ExpenseSubmission.PaidFor("p3", 2000),
                ),
            ),
            members(3),
        )

        val seeded = split.apply(members(4))
        assertEquals(listOf("p1", "p2", "p3"), seeded.filter { it.isIncluded }.map { it.id })
    }

    // ---- whether a remembered split still applies ----------------------------------------

    /**
     * 70/30 with the 30 removed is a percentage split that cannot be saved and nobody chose.
     * The whole split is discarded, not trimmed to the 70 that is left.
     */
    @Test
    fun `a split naming a departed participant is dropped whole, not trimmed`() {
        val split = DefaultSplit(
            splitMode = SplitMode.BY_PERCENTAGE,
            shares = mapOf("p1" to 7000L, "gone" to 3000L),
        )

        assertFalse(split.appliesTo(members(3)))

        val effective = split.orDefaultFor(members(3))
        assertEquals(SplitMode.EVENLY, effective.splitMode)
        assertNull(effective.shares)
    }

    /** Someone joining is not someone leaving, the remembered split still stands. */
    @Test
    fun `a new participant leaves a remembered split standing`() {
        val split = DefaultSplit(splitMode = SplitMode.EVENLY, shares = mapOf("p1" to 100L, "p2" to 100L))

        assertTrue(split.appliesTo(members(3)))
    }

    /** A `BY_AMOUNT` split remembers no names, so it never goes stale. */
    @Test
    fun `a BY_AMOUNT split always applies`() {
        assertTrue(DefaultSplit(splitMode = SplitMode.BY_AMOUNT).appliesTo(emptyList()))
    }

    /**
     * Every participant the split named has left, the group was rebuilt from scratch, or the
     * split arrived from a group this draft has never seen. Degrading to the plain default is
     * what keeps this a place a new expense can always start from, rather than a crash.
     */
    @Test
    fun `a saved split for a group whose participants have all changed degrades to the default rather than throwing`() {
        val stale = DefaultSplit(
            splitMode = SplitMode.BY_PERCENTAGE,
            shares = mapOf("old1" to 5000L, "old2" to 5000L),
        )
        val newParticipants = listOf(Participant("new1", "New One"), Participant("new2", "New Two"))

        val effective = stale.orDefaultFor(newParticipants)

        assertEquals(SplitMode.EVENLY, effective.splitMode)
        assertNull(effective.shares)

        val seeded = effective.apply(newParticipants)
        assertEquals(listOf("new1", "new2"), seeded.filter { it.isIncluded }.map { it.id })
    }

    // ---- applying a remembered split to a new expense -------------------------------------

    /** What a new expense starts from reproduces exactly what was remembered. */
    @Test
    fun `applying a saved split to a new expense reproduces the same shares`() {
        val split = DefaultSplit(
            splitMode = SplitMode.BY_PERCENTAGE,
            shares = mapOf("p1" to 7000L, "p2" to 2000L, "p3" to 1000L),
        )
        val participants = members(3)

        val seeded = split.apply(participants)
        val draft = ExpenseFormDraft(
            splitMode = split.splitMode,
            participants = seeded,
            groupCurrencyCode = "EUR",
        )

        assertEquals(
            listOf(7000L, 2000L, 1000L),
            seeded.map { draft.shareValue(it) },
        )
        assertTrue(seeded.all { it.isIncluded })
    }

    /** Fractions of a share survive the ×100 scale, a remembered 1.5 comes back as "1.5". */
    @Test
    fun `applying a remembered fractional share round-trips its text`() {
        val split = DefaultSplit(splitMode = SplitMode.BY_SHARES, shares = mapOf("p1" to 150L, "p2" to 100L))

        val seeded = split.apply(members(2), locale = AMERICAN)

        // Not "1.5": a remembered share is read back at the same fixed two-decimal precision
        // MoneyFormatter always writes a plain amount at, trailing zero included.
        assertEquals(listOf("1.50", "1"), seeded.map { it.valueText })
    }

    /** With nothing remembered at all, a new expense starts the way it always has: everyone in. */
    @Test
    fun `applying a BY_AMOUNT split includes everyone with nothing to type yet`() {
        val split = DefaultSplit(splitMode = SplitMode.BY_AMOUNT)

        val seeded = split.apply(members(3))

        assertTrue(seeded.all { it.isIncluded })
        assertEquals(SplitMode.BY_AMOUNT, split.splitMode)
    }
}
