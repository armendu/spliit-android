package app.spliit.android.ui.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** DESIGN.md §3's sign rules, as a mapping rather than as prose. */
class MoneySignTest {

    @Test
    fun `a positive balance is owed to you`() {
        assertEquals(MoneySign.POSITIVE, MoneySign.forBalance(150))
    }

    @Test
    fun `a negative balance is what you owe`() {
        assertEquals(MoneySign.NEGATIVE, MoneySign.forBalance(-150))
    }

    @Test
    fun `zero is settled, not a third colour pretending to be neutral`() {
        assertEquals(MoneySign.SETTLED, MoneySign.forBalance(0))
    }
}
