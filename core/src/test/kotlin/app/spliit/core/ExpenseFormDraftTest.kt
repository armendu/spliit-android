package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale

// Every test passes an explicit Locale; the machine default is never consulted.
private val AMERICAN = Locale.of("en", "US")
private val FRENCH = Locale.of("fr", "FR")

private val THE_DATE: Instant = Instant.parse("2025-03-04T18:30:00Z")

private fun members(count: Int): List<Participant> =
    (1..count).map { Participant("p$it", "Participant $it") }

private fun draft(
    currencyCode: String? = "EUR",
    participantCount: Int = 3,
    amountText: String = "10.00",
    locale: Locale = AMERICAN,
): ExpenseFormDraft = ExpenseFormDraft.creating(
    participants = members(participantCount),
    groupCurrencyCode = currencyCode,
    paidBy = "p1",
    locale = locale,
).copy(title = "Dinner", amountText = amountText, expenseDate = THE_DATE)

private fun ExpenseFormDraft.withShares(vararg values: String): ExpenseFormDraft =
    copy(
        participants = participants.mapIndexed { index, participant ->
            participant.copy(valueText = values.getOrElse(index) { participant.valueText })
        },
    )

private fun ExpenseFormDraft.wire(): ExpenseSubmission.Wire =
    requireNotNull(submission()) { "Expected a valid draft, found problems: $problems" }.toWire()

private fun ExpenseSubmission.Wire.shares(): List<Int> = paidFor.map { it.shares }

class ExpenseFormDraftTest {
    @Test
    fun `an evenly split expense sends 100 per participant`() {
        val wire = draft().wire()

        assertEquals(SplitMode.EVENLY, wire.splitMode)
        assertEquals(listOf(100, 100, 100), wire.shares())
    }

    @Test
    fun `BY_SHARES sends the share value times 100`() {
        val wire = draft()
            .withSplitMode(SplitMode.BY_SHARES)
            .withShares("2", "1", "3")
            .wire()

        assertEquals(listOf(200, 100, 300), wire.shares())
    }

    @Test
    fun `BY_SHARES carries a fractional share at hundredth precision`() {
        val wire = draft()
            .withSplitMode(SplitMode.BY_SHARES)
            .withShares("1.5", "1", "1")
            .wire()

        assertEquals(listOf(150, 100, 100), wire.shares())
    }

    @Test
    fun `BY_PERCENTAGE sends the percentage times 100`() {
        val wire = draft()
            .withSplitMode(SplitMode.BY_PERCENTAGE)
            .withShares("30", "20", "50")
            .wire()

        assertEquals(listOf(3000, 2000, 5000), wire.shares())
        assertEquals(10_000, wire.shares().sum())
    }

    @Test
    fun `BY_AMOUNT sends raw minor units rather than the value times 100`() {
        val wire = draft()
            .withSplitMode(SplitMode.BY_AMOUNT)
            .withShares("5.00", "3.00", "2.00")
            .wire()

        assertEquals(listOf(500, 300, 200), wire.shares())
        assertEquals(1000, wire.amount)
    }

    @Test
    fun `BY_AMOUNT in a yen group sends whole yen`() {
        val wire = draft(currencyCode = "JPY", amountText = "1000")
            .withSplitMode(SplitMode.BY_AMOUNT)
            .withShares("500", "300", "200")
            .wire()

        assertEquals(listOf(500, 300, 200), wire.shares())
        assertEquals(1000, wire.amount)
    }

    @Test
    fun `BY_AMOUNT in a three-decimal currency sends thousandths`() {
        val wire = draft(currencyCode = "KWD", amountText = "10.000")
            .withSplitMode(SplitMode.BY_AMOUNT)
            .withShares("5.000", "3.000", "2.000")
            .wire()

        assertEquals(listOf(5000, 3000, 2000), wire.shares())
        assertEquals(10_000, wire.amount)
    }

    // Easy to "fix" into a bug: the x100 modes don't follow the currency.
    @Test
    fun `EVENLY in a yen group still sends 100 per participant`() {
        val wire = draft(currencyCode = "JPY", amountText = "1000").wire()

        assertEquals(listOf(100, 100, 100), wire.shares())
        assertEquals(1000, wire.amount)
    }

    @Test
    fun `BY_SHARES in a yen group still sends the share value times 100`() {
        val wire = draft(currencyCode = "JPY", amountText = "1000")
            .withSplitMode(SplitMode.BY_SHARES)
            .withShares("2", "1", "1")
            .wire()

        assertEquals(listOf(200, 100, 100), wire.shares())
    }

    @Test
    fun `BY_PERCENTAGE in a yen group still sends the percentage times 100`() {
        val wire = draft(currencyCode = "JPY", amountText = "1000")
            .withSplitMode(SplitMode.BY_PERCENTAGE)
            .withShares("30", "20", "50")
            .wire()

        assertEquals(listOf(3000, 2000, 5000), wire.shares())
    }

    @Test
    fun `an even split apportions the remainder in whole minor units`() {
        val amounts = draft().splitAmounts()

        assertEquals(listOf(334L, 333L, 333L), amounts.map { it.amount })
        assertEquals(1000L, amounts.sumOf { it.amount })
    }

    @Test
    fun `the extra minor unit goes to the first included participant`() {
        val amounts = draft().splitAmounts()

        assertEquals("p1", amounts.first().participantId)
        assertEquals(334L, amounts.first().amount)
        assertEquals(listOf("p1", "p2", "p3"), amounts.map { it.participantId })
    }

    @Test
    fun `the extra minor unit goes to the first participant actually in the split`() {
        val amounts = draft(participantCount = 4)
            .withParticipantIncluded("p1", false)
            .splitAmounts()

        assertEquals(listOf("p2", "p3", "p4"), amounts.map { it.participantId })
        assertEquals(listOf(334L, 333L, 333L), amounts.map { it.amount })
    }

    @Test
    fun `an even split that divides exactly leaves no remainder`() {
        val amounts = draft(participantCount = 4, amountText = "10.00").splitAmounts()

        assertEquals(listOf(250L, 250L, 250L, 250L), amounts.map { it.amount })
    }

    @Test
    fun `an even split in a yen group apportions whole yen`() {
        val amounts = draft(currencyCode = "JPY", amountText = "1000").splitAmounts()

        assertEquals(listOf(334L, 333L, 333L), amounts.map { it.amount })
    }

    @Test
    fun `a shares split apportions the remainder too`() {
        val amounts = draft(amountText = "10.00")
            .withSplitMode(SplitMode.BY_SHARES)
            .withShares("1", "1", "1")
            .splitAmounts()

        assertEquals(1000L, amounts.sumOf { it.amount })
        assertEquals(listOf(334L, 333L, 333L), amounts.map { it.amount })
    }

    @Test
    fun `a by-amount split is apportioned by what was typed`() {
        val amounts = draft()
            .withSplitMode(SplitMode.BY_AMOUNT)
            .withShares("5.00", "3.00", "2.00")
            .splitAmounts()

        assertEquals(listOf(500L, 300L, 200L), amounts.map { it.amount })
    }

    @Test
    fun `a converted expense keeps two amounts on two different scales`() {
        val wire = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .copy(originalAmountText = "40.00", conversionRateText = "163.5")
            .wire()

        assertEquals(4000, wire.originalAmount)
        assertEquals(6540, wire.amount)
        assertEquals("EUR", wire.originalCurrency)
        assertEquals(BigDecimal("163.5"), wire.conversionRate)
    }

    @Test
    fun `a yen expense in a euro group converts at the euro's precision`() {
        val wire = draft(currencyCode = "EUR", amountText = "")
            .withCurrency("JPY")
            .copy(originalAmountText = "6540", conversionRateText = "0.0061")
            .wire()

        assertEquals(6540, wire.originalAmount)
        assertEquals(3989, wire.amount)
    }

    @Test
    fun `an expense in the group's own currency is not converted`() {
        val draft = draft(currencyCode = "EUR").withCurrency("EUR")

        assertFalse(draft.conversionRequired)
        assertEquals(1000L, draft.amountMinorUnits)
    }

    @Test
    fun `a lowercase currency code is normalised before it is sent`() {
        val wire = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("eur")
            .copy(originalAmountText = "40.00", conversionRateText = "163.5")
            .wire()

        assertEquals("EUR", wire.originalCurrency)
    }

    @Test
    fun `dropping the conversion sends a null currency and omits the other two`() {
        val wire = draft(currencyCode = "EUR").withCurrency(null).wire()

        assertNull(wire.originalCurrency)
        assertNull(wire.originalAmount)
        assertNull(wire.conversionRate)
    }

    @Test
    fun `an unconverted expense says nothing about any of the three`() {
        val wire = draft(currencyCode = "EUR").wire()

        assertNull(wire.originalCurrency)
        assertNull(wire.originalAmount)
        assertNull(wire.conversionRate)
    }

    @Test
    fun `dropping the conversion keeps the converted total in the amount field`() {
        val converted = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .copy(originalAmountText = "40.00", conversionRateText = "163.5")

        assertEquals(6540L, converted.withCurrency("JPY").amountMinorUnits)
    }

    @Test
    fun `changing the currency drops the rate but keeps what was paid`() {
        val moved = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .copy(originalAmountText = "40.00", conversionRateText = "163.5")
            .withCurrency("USD")

        assertEquals("40.00", moved.originalAmountText)
        assertEquals("", moved.conversionRateText)
    }

    @Test
    fun `a group with no ISO code cannot convert`() {
        val draft = draft(currencyCode = null).withCurrency("EUR")

        assertFalse(draft.conversionRequired)
    }

    @Test
    fun `editing round-trips every field unchanged`() {
        val existing = ExpenseFormDraft.ExistingExpense(
            title = "Hotel",
            expenseDate = THE_DATE,
            amount = 18_482,
            categoryId = 7,
            paidById = "p2",
            paidFor = listOf(
                ExpenseFormDraft.ExistingExpense.PaidFor("p1", 200),
                ExpenseFormDraft.ExistingExpense.PaidFor("p3", 100),
            ),
            splitMode = SplitMode.BY_SHARES,
            isReimbursement = true,
            notes = "Two nights",
            recurrenceRule = "MONTHLY",
            originalAmount = 20_000,
            originalCurrency = "USD",
            conversionRate = BigDecimal("0.9241"),
        )

        val wire = ExpenseFormDraft.editing(
            expense = existing,
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        ).wire()

        assertEquals("Hotel", wire.title)
        assertEquals(THE_DATE, wire.expenseDate)
        assertEquals(18_482, wire.amount)
        assertEquals(7, wire.category)
        assertEquals("p2", wire.paidBy)
        assertEquals(SplitMode.BY_SHARES, wire.splitMode)
        assertEquals(listOf("p1" to 200, "p3" to 100), wire.paidFor.map { it.participant to it.shares })
        assertTrue(wire.isReimbursement)
        assertEquals("Two nights", wire.notes)
        assertEquals("MONTHLY", wire.recurrenceRule)
        assertEquals("USD", wire.originalCurrency)
        assertEquals(20_000, wire.originalAmount)
        assertEquals(BigDecimal("0.9241"), wire.conversionRate)
    }

    @Test
    fun `editing round-trips a recurrence rule this client cannot name`() {
        val wire = ExpenseFormDraft.editing(
            expense = existingExpense(recurrenceRule = "FORTNIGHTLY"),
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        ).wire()

        assertEquals("FORTNIGHTLY", wire.recurrenceRule)
    }

    @Test
    fun `editing a by-amount expense round-trips its shares as minor units`() {
        val wire = ExpenseFormDraft.editing(
            expense = existingExpense(
                amount = 1000,
                splitMode = SplitMode.BY_AMOUNT,
                paidFor = listOf(
                    ExpenseFormDraft.ExistingExpense.PaidFor("p1", 500),
                    ExpenseFormDraft.ExistingExpense.PaidFor("p2", 300),
                    ExpenseFormDraft.ExistingExpense.PaidFor("p3", 200),
                ),
            ),
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        ).wire()

        assertEquals(listOf(500, 300, 200), wire.shares())
    }

    @Test
    fun `editing a by-amount expense in a yen group round-trips whole yen`() {
        val wire = ExpenseFormDraft.editing(
            expense = existingExpense(
                amount = 1000,
                splitMode = SplitMode.BY_AMOUNT,
                paidFor = listOf(
                    ExpenseFormDraft.ExistingExpense.PaidFor("p1", 600),
                    ExpenseFormDraft.ExistingExpense.PaidFor("p2", 400),
                ),
            ),
            participants = members(3),
            groupCurrencyCode = "JPY",
            locale = AMERICAN,
        ).wire()

        assertEquals(listOf(600, 400), wire.shares())
    }

    @Test
    fun `editing a converted expense derives the total from the rate rather than trusting it`() {
        val wire = ExpenseFormDraft.editing(
            expense = existingExpense(
                amount = 99_999,
                originalCurrency = "USD",
                originalAmount = 20_000,
                conversionRate = BigDecimal("0.9241"),
            ),
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        ).wire()

        assertEquals(18_482, wire.amount)
    }

    @Test
    fun `editing keeps only the participants the expense was paid for`() {
        val draft = ExpenseFormDraft.editing(
            expense = existingExpense(
                paidFor = listOf(ExpenseFormDraft.ExistingExpense.PaidFor("p2", 100)),
            ),
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        )

        assertEquals(3, draft.participants.size)
        assertEquals(listOf("p2"), draft.includedParticipants.map { it.id })
    }

    @Test
    fun `editing an expense with no original currency ignores the other two fields`() {
        val draft = ExpenseFormDraft.editing(
            expense = existingExpense(
                originalCurrency = null,
                originalAmount = 20_000,
                conversionRate = BigDecimal("0.9241"),
            ),
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        )

        assertFalse(draft.conversionRequired)
        assertEquals("", draft.originalAmountText)
        assertEquals("", draft.conversionRateText)

        val wire = draft.wire()
        assertNull(wire.originalCurrency)
        assertNull(wire.originalAmount)
        assertNull(wire.conversionRate)
    }

    @Test
    fun `editing a converted expense reads the original amount at its own precision`() {
        val draft = ExpenseFormDraft.editing(
            expense = existingExpense(
                amount = 3989,
                originalCurrency = "JPY",
                originalAmount = 6540,
                conversionRate = BigDecimal("0.0061"),
            ),
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        )

        assertEquals("6540", draft.originalAmountText)
        assertEquals(6540L, draft.originalAmountMinorUnits)
    }

    @Test
    fun `editing reads what was typed in the reader's own locale`() {
        val draft = ExpenseFormDraft.editing(
            expense = existingExpense(amount = 123_456),
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = FRENCH,
        )

        assertEquals(123_456L, draft.amountMinorUnits)
        assertTrue(draft.amountText.contains(','), "French writes 1234,56, found ${draft.amountText}")
    }

    @Test
    fun `a complete draft is valid`() {
        assertTrue(draft().isValid, "Unexpected problems: ${draft().problems}")
    }

    @Test
    fun `a title is required`() {
        val problems = draft().copy(title = "   ").problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.TitleTooShort))
    }

    @Test
    fun `a validation failure names the field it belongs to`() {
        val problems = draft().copy(title = "").problems

        assertEquals(listOf(ExpenseFormDraft.Field.TITLE), problems.map { it.field })
        assertEquals(problems, draft().copy(title = "").problems(ExpenseFormDraft.Field.TITLE))
        assertTrue(draft().copy(title = "").problems(ExpenseFormDraft.Field.AMOUNT).isEmpty())
    }

    @Test
    fun `the amount must not be missing`() {
        assertTrue(draft(amountText = "").problems.contains(ExpenseFormDraft.Problem.AmountMissing))
    }

    @Test
    fun `the amount must be a number`() {
        val problems = draft(amountText = "lots").problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.AmountNotANumber))
        assertEquals(listOf(ExpenseFormDraft.Field.AMOUNT), problems.map { it.field })
    }

    @Test
    fun `the amount must not be zero`() {
        assertTrue(draft(amountText = "0.00").problems.contains(ExpenseFormDraft.Problem.AmountZero))
    }

    @Test
    fun `a negative amount is rejected`() {
        val problems = draft(amountText = "-10.00").problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.AmountNegative), "Found $problems")
        assertEquals(ExpenseFormDraft.Field.AMOUNT, problems.single().field)
        assertFalse(draft(amountText = "-10.00").isValid)
    }

    @Test
    fun `a negative original amount is rejected`() {
        val problems = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .copy(originalAmountText = "-40.00", conversionRateText = "163.5")
            .problems

        assertTrue(
            problems.contains(ExpenseFormDraft.Problem.OriginalAmountNegative),
            "Found $problems",
        )
        assertTrue(problems.any { it.field == ExpenseFormDraft.Field.ORIGINAL_AMOUNT })
    }

    @Test
    fun `a negative conversion rate is rejected`() {
        val problems = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .copy(originalAmountText = "40.00", conversionRateText = "-163.5")
            .problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.ConversionRateNotPositive))
    }

    @Test
    fun `the amount has a ceiling`() {
        assertTrue(
            draft(amountText = "10000000.01").problems.contains(ExpenseFormDraft.Problem.AmountTooLarge),
        )
        assertFalse(
            draft(amountText = "10000000.00").problems.contains(ExpenseFormDraft.Problem.AmountTooLarge),
        )
    }

    @Test
    fun `a payer must be chosen`() {
        val problems = draft().copy(paidById = null).problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.PayerMissing))
        assertEquals(ExpenseFormDraft.Field.PAID_BY, problems.single().field)
    }

    @Test
    fun `at least one participant must be paid for`() {
        val problems = draft().withAllParticipantsIncluded(false).problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.NoParticipantsSelected))
        assertEquals(ExpenseFormDraft.Field.PAID_FOR, problems.single().field)
    }

    @Test
    fun `percentages must total 100`() {
        val problems = draft()
            .withSplitMode(SplitMode.BY_PERCENTAGE)
            .withShares("30", "20", "49")
            .problems

        assertTrue(
            problems.contains(ExpenseFormDraft.Problem.PercentagesDoNotSumTo100(difference = 100)),
            "Expected a 1% shortfall, found $problems",
        )
        assertEquals(ExpenseFormDraft.Field.PAID_FOR, problems.single().field)
    }

    @Test
    fun `percentages totalling 100 are accepted`() {
        val draft = draft()
            .withSplitMode(SplitMode.BY_PERCENTAGE)
            .withShares("33.33", "33.33", "33.34")

        assertTrue(draft.isValid, "Unexpected problems: ${draft.problems}")
        assertEquals(0L, draft.unallocated)
    }

    @Test
    fun `by-amount shares must total the expense amount`() {
        val problems = draft()
            .withSplitMode(SplitMode.BY_AMOUNT)
            .withShares("5.00", "3.00", "1.00")
            .problems

        assertTrue(
            problems.contains(ExpenseFormDraft.Problem.AmountsDoNotSumToTotal(difference = 100)),
            "Expected 1.00 left to allocate, found $problems",
        )
    }

    @Test
    fun `by-amount shares totalling the amount are accepted`() {
        val draft = draft()
            .withSplitMode(SplitMode.BY_AMOUNT)
            .withShares("3.34", "3.33", "3.33")

        assertTrue(draft.isValid, "Unexpected problems: ${draft.problems}")
    }

    @Test
    fun `by-amount shares in a yen group total whole yen`() {
        val draft = draft(currencyCode = "JPY", amountText = "1000")
            .withSplitMode(SplitMode.BY_AMOUNT)
            .withShares("500", "300", "200")

        assertTrue(draft.isValid, "Unexpected problems: ${draft.problems}")
        assertEquals(0L, draft.unallocated)
    }

    @Test
    fun `a share that is not a number names the participant it belongs to`() {
        val problems = draft()
            .withSplitMode(SplitMode.BY_SHARES)
            .withShares("2", "some", "1")
            .problems

        assertEquals(
            listOf(ExpenseFormDraft.Problem.ShareNotANumber("p2")),
            problems,
        )
        assertEquals(ExpenseFormDraft.Field.PAID_FOR, problems.single().field)
        assertEquals(problems, draft().withSplitMode(SplitMode.BY_SHARES).withShares("2", "some", "1").problems("p2"))
    }

    @Test
    fun `a share of zero is rejected`() {
        val problems = draft()
            .withSplitMode(SplitMode.BY_SHARES)
            .withShares("1", "0", "1")
            .problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.ShareNotPositive("p2")))
    }

    @Test
    fun `a share typed by an excluded participant is ignored`() {
        val draft = draft()
            .withSplitMode(SplitMode.BY_SHARES)
            .withShares("1", "nonsense", "1")
            .withParticipantIncluded("p2", false)

        assertTrue(draft.isValid, "Unexpected problems: ${draft.problems}")
    }

    @Test
    fun `a conversion needs an amount and a rate`() {
        val problems = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.OriginalAmountMissing))
        assertTrue(problems.contains(ExpenseFormDraft.Problem.ConversionRateMissing))
        assertEquals(
            listOf(ExpenseFormDraft.Field.ORIGINAL_AMOUNT, ExpenseFormDraft.Field.CONVERSION_RATE),
            problems.map { it.field },
        )
    }

    @Test
    fun `a conversion rate must be positive`() {
        val problems = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .copy(originalAmountText = "40.00", conversionRateText = "0")
            .problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.ConversionRateNotPositive))
    }

    @Test
    fun `a conversion that rounds to nothing is a zero amount`() {
        val problems = draft(currencyCode = "JPY", amountText = "")
            .withCurrency("EUR")
            .copy(originalAmountText = "0.01", conversionRateText = "0.001")
            .problems

        assertTrue(problems.contains(ExpenseFormDraft.Problem.AmountZero))
    }

    @Test
    fun `an invalid draft produces no submission`() {
        assertNull(draft().copy(title = "").submission())
        assertNotNull(draft().submission())
    }

    @Test
    fun `switching split mode keeps the participants`() {
        val before = draft(participantCount = 4).withParticipantIncluded("p4", false)

        for (mode in SplitMode.entries) {
            val after = before.withSplitMode(mode)

            assertEquals(before.participants.map { it.id }, after.participants.map { it.id })
            assertEquals(
                listOf("p1", "p2", "p3"),
                after.includedParticipants.map { it.id },
                "$mode dropped somebody",
            )
            assertEquals(mode, after.splitMode)
        }
    }

    @Test
    fun `switching to by-amount seeds shares that total the expense`() {
        val after = draft().withSplitMode(SplitMode.BY_AMOUNT)

        assertEquals(listOf("3.34", "3.33", "3.33"), after.participants.map { it.valueText })
        assertTrue(after.isValid, "Unexpected problems: ${after.problems}")
    }

    @Test
    fun `switching to by-percentage seeds percentages that total 100`() {
        val after = draft().withSplitMode(SplitMode.BY_PERCENTAGE)

        assertEquals(0L, after.unallocated)
        assertTrue(after.isValid, "Unexpected problems: ${after.problems}")
    }

    @Test
    fun `switching to by-shares seeds one share each`() {
        val after = draft().withSplitMode(SplitMode.BY_SHARES)

        assertEquals(listOf("1", "1", "1"), after.participants.map { it.valueText })
        assertTrue(after.isValid)
    }

    @Test
    fun `a new draft has everyone in and the payer that was asked for`() {
        val draft = ExpenseFormDraft.creating(
            participants = members(3),
            groupCurrencyCode = "EUR",
            paidBy = "p2",
            locale = AMERICAN,
        )

        assertEquals("p2", draft.paidById)
        assertEquals(3, draft.includedParticipants.size)
        assertEquals(SplitMode.EVENLY, draft.splitMode)
        assertEquals("EUR", draft.originalCurrencyCode)
    }

    @Test
    fun `a new draft falls back to the first participant when the payer has left`() {
        val draft = ExpenseFormDraft.creating(
            participants = members(3),
            groupCurrencyCode = "EUR",
            paidBy = "gone",
            locale = AMERICAN,
        )

        assertEquals("p1", draft.paidById)
    }

    @Test
    fun `selecting all and none leaves the typed shares alone`() {
        val typed = draft().withSplitMode(SplitMode.BY_SHARES).withShares("2", "3", "4")

        val roundTripped = typed.withAllParticipantsIncluded(false).withAllParticipantsIncluded(true)

        assertEquals(listOf("2", "3", "4"), roundTripped.participants.map { it.valueText })
        assertTrue(roundTripped.allParticipantsIncluded)
    }

    @Test
    fun `notes are trimmed away when they are blank`() {
        assertNull(draft().copy(notes = "   ").wire().notes)
        assertEquals("Split with Ana", draft().copy(notes = "  Split with Ana  ").wire().notes)
    }

    @Test
    fun `the title is trimmed on the way out`() {
        assertEquals("Dinner", draft().copy(title = "  Dinner  ").wire().title)
    }

    @Test
    fun `an amount typed in French is read as French`() {
        val wire = draft(locale = FRENCH, amountText = "12,34").wire()

        assertEquals(1234, wire.amount)
    }

    @Test
    fun `saving the split as a default is off unless it is asked for`() {
        assertFalse(draft().wire().saveDefaultSplittingOptions)
        assertTrue(draft().copy(saveSplitAsDefault = true).wire().saveDefaultSplittingOptions)
    }

    @Test
    fun `minor units narrow to the wire's Int in one place`() {
        assertEquals(1234, MinorUnits.toWire(1234L))
        assertEquals(1234L, MinorUnits.fromWire(1234))
        assertThrows(ArithmeticException::class.java) {
            MinorUnits.toWire(Int.MAX_VALUE.toLong() + 1)
        }
    }

    @Test
    fun `a new draft starts from the group's saved split`() {
        val remembered = DefaultSplit(
            splitMode = SplitMode.BY_PERCENTAGE,
            shares = mapOf("p1" to 7000L, "p2" to 3000L),
        )

        val draft = ExpenseFormDraft.creating(
            participants = members(3),
            groupCurrencyCode = "EUR",
            paidBy = "p1",
            defaultSplit = remembered,
            locale = AMERICAN,
        )

        assertEquals(SplitMode.BY_PERCENTAGE, draft.splitMode)
        assertEquals(listOf("70", "30", "1"), draft.participants.map { it.valueText })
        assertEquals(listOf("p1", "p2"), draft.includedParticipants.map { it.id })
    }

    @Test
    fun `a saved split naming a departed participant falls back to the group's default`() {
        val stale = DefaultSplit(
            splitMode = SplitMode.BY_SHARES,
            shares = mapOf("p1" to 200L, "gone" to 100L),
        )

        val draft = ExpenseFormDraft.creating(
            participants = members(3),
            groupCurrencyCode = "EUR",
            defaultSplit = stale,
            locale = AMERICAN,
        )

        assertEquals(SplitMode.EVENLY, draft.splitMode)
        assertEquals(3, draft.includedParticipants.size)
    }

    @Test
    fun `an even split of the whole group covers somebody who joined since`() {
        val remembered = DefaultSplit(splitMode = SplitMode.EVENLY, shares = null)

        val draft = ExpenseFormDraft.creating(
            participants = members(4),
            groupCurrencyCode = "EUR",
            defaultSplit = remembered,
            locale = AMERICAN,
        )

        assertEquals(4, draft.includedParticipants.size)
    }

    @Test
    fun `a settle-up is one person paying one other, flagged as a reimbursement`() {
        val draft = ExpenseFormDraft.settling(
            fromParticipantId = "p3",
            toParticipantId = "p1",
            amountMinorUnits = 1250L,
            title = "Reimbursement",
            participants = members(3),
            groupCurrencyCode = "EUR",
            locale = AMERICAN,
        )

        assertEquals("p3", draft.paidById)
        assertEquals(listOf("p1"), draft.includedParticipants.map { it.id })
        assertEquals("12.50", draft.amountText)
        assertEquals(SplitMode.EVENLY, draft.splitMode)
        assertTrue(draft.isReimbursement)
        assertFalse(draft.isSplitWorthRemembering)
        assertTrue(draft.isValid)

        val submission = checkNotNull(draft.submission())
        assertEquals(1250L, submission.amount)
        assertEquals(listOf(ExpenseSubmission.PaidFor("p1", 100L)), submission.paidFor)
    }

    @Test
    fun `a settle-up in a zero-decimal currency is not divided by a hundred`() {
        val draft = ExpenseFormDraft.settling(
            fromParticipantId = "p2",
            toParticipantId = "p1",
            amountMinorUnits = 1250L,
            title = "Reimbursement",
            participants = members(2),
            groupCurrencyCode = "JPY",
            locale = AMERICAN,
        )

        assertEquals("1250", draft.amountText)
        assertEquals(1250L, draft.amountMinorUnits)
    }
}

private fun existingExpense(
    amount: Int = 1000,
    splitMode: SplitMode = SplitMode.EVENLY,
    paidFor: List<ExpenseFormDraft.ExistingExpense.PaidFor> = listOf(
        ExpenseFormDraft.ExistingExpense.PaidFor("p1", 100),
        ExpenseFormDraft.ExistingExpense.PaidFor("p2", 100),
        ExpenseFormDraft.ExistingExpense.PaidFor("p3", 100),
    ),
    recurrenceRule: String = "NONE",
    originalAmount: Int? = null,
    originalCurrency: String? = null,
    conversionRate: BigDecimal? = null,
): ExpenseFormDraft.ExistingExpense = ExpenseFormDraft.ExistingExpense(
    title = "Dinner",
    expenseDate = THE_DATE,
    amount = amount,
    categoryId = 0,
    paidById = "p1",
    paidFor = paidFor,
    splitMode = splitMode,
    isReimbursement = false,
    notes = null,
    recurrenceRule = recurrenceRule,
    originalAmount = originalAmount,
    originalCurrency = originalCurrency,
    conversionRate = conversionRate,
)
