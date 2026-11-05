package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale
import kotlin.random.Random

// Seeded, so a failure reproduces.
class ApportionPropertyTest {
    private val currencies = listOf("JPY" to 0, "EUR" to 2, "KWD" to 3)

    @Test
    fun `a split always sums to the total, each share within one unit of exact, in every precision`() {
        val random = Random(20261104)
        repeat(2_000) { case ->
            val (code, digits) = currencies[random.nextInt(currencies.size)]
            val count = random.nextInt(1, 9)
            val total = random.nextLong(-1_000_000_00L, 1_000_000_00L).takeIf { it != 0L } ?: 1L
            val weights = List(count) { random.nextLong(1, 1_000_00) }
            val people = List(count) { Participant("p$it", "P$it") }

            val draft = ExpenseFormDraft.creating(people, code, paidBy = "p0", locale = Locale.US)
                .withSplitMode(SplitMode.BY_SHARES)
                .copy(
                    title = "Case $case",
                    expenseDate = Instant.EPOCH,
                    amountText = BigDecimal.valueOf(total, digits).toPlainString(),
                    participants = people.mapIndexed { index, person ->
                        ParticipantShareDraft(
                            person.id,
                            person.name,
                            isIncluded = true,
                            valueText = BigDecimal.valueOf(weights[index], 2).toPlainString(),
                        )
                    },
                )
            val amounts = draft.splitAmounts().map { it.amount }
            val context = "case $case: $total in $code over $weights gave $amounts"

            assertEquals(count, amounts.size, context)
            assertEquals(total, amounts.sum(), context)
            val weightSum = BigDecimal.valueOf(weights.sum())
            amounts.zip(weights).forEach { (amount, weight) ->
                val exact = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(weight)).divide(weightSum, 6, RoundingMode.HALF_EVEN)
                assertTrue((BigDecimal.valueOf(amount) - exact).abs() < BigDecimal.ONE, context)
            }
            assertEquals(amounts, draft.splitAmounts().map { it.amount }, "not order-stable: $context")
        }
    }
}
