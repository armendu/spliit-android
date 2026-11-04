package app.spliit.android.feature.expense

import app.spliit.android.feature.groups.message
import app.spliit.core.ExpenseFormDraft
import app.spliit.core.ExpenseFormDraft.Problem
import app.spliit.core.GroupFormDraft
import app.spliit.core.MoneyFormatter
import app.spliit.core.Participant
import app.spliit.core.SplitMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class ProblemTextTest {
    private val euros = MoneyFormatter("€", "EUR", Locale.US)
    private val yen = MoneyFormatter("¥", "JPY", Locale.US)

    @Test
    fun `an amount split names what is left or over in the group's currency`() {
        assertEquals("€2.50 still to allocate.", Problem.AmountsDoNotSumToTotal(250).message(euros))
        assertEquals("€1.00 over the total.", Problem.AmountsDoNotSumToTotal(-100).message(euros))
        assertEquals("¥250 still to allocate.", Problem.AmountsDoNotSumToTotal(250).message(yen))
    }

    @Test
    fun `a percentage split names what is left or over without trailing zeros`() {
        assertEquals("25% still to allocate.", Problem.PercentagesDoNotSumTo100(2500).message(euros))
        assertEquals("10.5% over 100%.", Problem.PercentagesDoNotSumTo100(-1050).message(euros))
    }

    @Test
    fun `every other expense problem has something to say`() {
        val problems = listOf(
            Problem.TitleTooShort, Problem.TitleTooLong, Problem.NotesTooLong, Problem.AmountMissing,
            Problem.AmountNotANumber, Problem.AmountZero, Problem.AmountNegative, Problem.AmountTooLarge,
            Problem.OriginalAmountMissing, Problem.OriginalAmountNotANumber, Problem.OriginalAmountZero,
            Problem.OriginalAmountNegative, Problem.OriginalAmountTooLarge, Problem.ConversionRateMissing,
            Problem.ConversionRateNotANumber, Problem.ConversionRateNotPositive, Problem.PayerMissing,
            Problem.NoParticipantsSelected, Problem.ShareNotANumber("p1"), Problem.ShareNotPositive("p1"),
        )
        problems.forEach { assertTrue(it.message(euros).isNotBlank(), "no message for $it") }
        assertTrue("200" in Problem.TitleTooLong.message(euros))
        assertTrue("5000" in Problem.NotesTooLong.message(euros))
    }

    @Test
    fun `every group problem has something to say, with the limit where there is one`() {
        val problems = listOf(
            GroupFormDraft.Problem.NameRequired, GroupFormDraft.Problem.NameTooShort,
            GroupFormDraft.Problem.NameTooLong, GroupFormDraft.Problem.InformationTooLong,
            GroupFormDraft.Problem.CurrencySymbolRequired, GroupFormDraft.Problem.CurrencySymbolTooLong,
            GroupFormDraft.Problem.NoParticipants, GroupFormDraft.Problem.ParticipantNameRequired("p1"),
            GroupFormDraft.Problem.ParticipantNameTooShort("p1"), GroupFormDraft.Problem.ParticipantNameTooLong("p1"),
            GroupFormDraft.Problem.DuplicateParticipantName("p1"),
        )
        problems.forEach { assertTrue(it.message().isNotBlank(), "no message for $it") }
        assertTrue("50" in GroupFormDraft.Problem.NameTooLong.message())
        assertTrue("10000" in GroupFormDraft.Problem.InformationTooLong.message())
        assertTrue("5" in GroupFormDraft.Problem.CurrencySymbolTooLong.message())
    }

    private fun draft(mode: SplitMode, amount: String) = ExpenseFormDraft.creating(
        participants = listOf(Participant("p1", "Ana"), Participant("p2", "Bruno")),
        groupCurrencyCode = "EUR",
        paidBy = "p1",
        locale = Locale.US,
    ).copy(title = "Dinner", amountText = "10.00").withSplitMode(mode).copy(amountText = amount)

    @Test
    fun `the running tally under a split shows only what is left or over`() {
        assertEquals("€2.00 still to allocate.", draft(SplitMode.BY_AMOUNT, "12.00").remainderText(euros))
        assertEquals("€1.00 over the total.", draft(SplitMode.BY_AMOUNT, "9.00").remainderText(euros))
        assertNull(draft(SplitMode.BY_AMOUNT, "10.00").remainderText(euros))
        assertNull(draft(SplitMode.EVENLY, "12.00").remainderText(euros))
    }

    @Test
    fun `each split mode has its own title, explanation and unit`() {
        val modes = SplitMode.entries
        assertEquals(modes.size, modes.map { it.title() }.toSet().size)
        assertEquals(modes.size, modes.map { it.explanation() }.toSet().size)
        assertEquals("€", SplitMode.BY_AMOUNT.unitLabel("€"))
        assertEquals("", SplitMode.EVENLY.unitLabel("€"))
    }
}
