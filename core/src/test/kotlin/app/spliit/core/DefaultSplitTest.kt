package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale

private val AMERICAN = Locale.of("en", "US")

private val THE_DATE: Instant = Instant.parse("2025-03-04T18:30:00Z")

private fun members(count: Int): List<Participant> =
    (1..count).map { Participant("p$it", "Participant $it") }

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

        val seeded = split.apply(members(4))
        assertEquals(listOf("p1", "p2", "p3", "p4"), seeded.filter { it.isIncluded }.map { it.id })
    }

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

    @Test
    fun `a new participant leaves a remembered split standing`() {
        val split = DefaultSplit(splitMode = SplitMode.EVENLY, shares = mapOf("p1" to 100L, "p2" to 100L))

        assertTrue(split.appliesTo(members(3)))
    }

    @Test
    fun `a BY_AMOUNT split always applies`() {
        assertTrue(DefaultSplit(splitMode = SplitMode.BY_AMOUNT).appliesTo(emptyList()))
    }

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

    @Test
    fun `applying a remembered fractional share round-trips its text`() {
        val split = DefaultSplit(splitMode = SplitMode.BY_SHARES, shares = mapOf("p1" to 150L, "p2" to 100L))

        val seeded = split.apply(members(2), locale = AMERICAN)

        assertEquals(listOf("1.50", "1"), seeded.map { it.valueText })
    }

    @Test
    fun `applying a BY_AMOUNT split includes everyone with nothing to type yet`() {
        val split = DefaultSplit(splitMode = SplitMode.BY_AMOUNT)

        val seeded = split.apply(members(3))

        assertTrue(seeded.all { it.isIncluded })
        assertEquals(SplitMode.BY_AMOUNT, split.splitMode)
    }
}
