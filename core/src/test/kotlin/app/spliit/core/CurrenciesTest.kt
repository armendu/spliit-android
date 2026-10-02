package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.Collator
import java.util.Locale

private val AMERICAN = Locale.of("en", "US")
private val FRENCH = Locale.of("fr", "FR")

class CurrenciesTest {
    @Test
    fun `the picker is offered a list of real ISO codes`() {
        val currencies = Currencies.all(AMERICAN)

        assertTrue(currencies.size > 100, "Only ${currencies.size} currencies.")
        assertTrue(currencies.all { it.code.length == 3 })
        assertTrue(currencies.all { it.code == it.code.uppercase(Locale.ROOT) })
        assertTrue(currencies.all { it.name.isNotBlank() && it.symbol.isNotBlank() })
        assertEquals(currencies.map { it.code }.distinct().size, currencies.size, "Duplicate codes.")

        for (code in listOf("USD", "EUR", "GBP", "CHF", "JPY", "KWD")) {
            assertTrue(currencies.any { it.code == code }, "$code is missing.")
        }
    }

    @Test
    fun `the list knows that minor units are not always hundredths`() {
        val currencies = Currencies.all(AMERICAN)

        assertEquals(0, currencies.single { it.code == "JPY" }.minorUnitDigits)
        assertEquals(3, currencies.single { it.code == "KWD" }.minorUnitDigits)
        assertEquals(2, currencies.single { it.code == "USD" }.minorUnitDigits)

        assertTrue(currencies.count { it.minorUnitDigits == 0 } >= 10, "Zero-decimal currencies vanished.")
        assertTrue(currencies.count { it.minorUnitDigits == 3 } >= 5, "Three-decimal currencies vanished.")
        assertTrue(currencies.none { it.minorUnitDigits < 0 }, "Negative precision would throw at format time.")
    }

    @Test
    fun `names and symbols come from the JDK in the reader's language`() {
        val american = Currencies.named("USD", AMERICAN)!!
        val french = Currencies.named("USD", FRENCH)!!

        assertTrue(american.name.contains("dollar", ignoreCase = true), american.name)
        assertTrue(french.name.contains("dollar", ignoreCase = true), french.name)
        assertTrue(french.name.contains("états-unis", ignoreCase = true), french.name)
        assertNotEquals(american.name, french.name)

        assertEquals("\$", american.symbol)
        assertEquals("\$US", french.symbol)
    }

    @Test
    fun `a code is read however it was stored`() {
        assertEquals("CHF", Currencies.named("chf", AMERICAN)?.code)
        assertEquals("EUR", Currencies.named(" eur ", AMERICAN)?.code)
    }

    @Test
    fun `anything that is not a currency code is not a currency`() {
        assertNull(Currencies.named("", AMERICAN))
        assertNull(Currencies.named("US", AMERICAN))
        assertNull(Currencies.named("DOLLARS", AMERICAN))
        assertNull(Currencies.named("ZZZ", AMERICAN))
    }

    @Test
    fun `a currency outside the list can still be named, at a usable precision`() {
        val gold = Currencies.named("XAU", AMERICAN)

        assertEquals("XAU", gold?.code)
        assertEquals(2, gold?.minorUnitDigits)
        assertFalse(Currencies.all(AMERICAN).any { it.code == "XAU" }, "The picker should not offer gold.")
    }

    @Test
    fun `names are sorted with a collator, not by code point`() {
        val french = localizedOrder(FRENCH)

        assertTrue(french.compare("Épicerie", "Vêtements") < 0)
        assertTrue("Épicerie" > "Vêtements", "The premise: a naive sort disagrees.")

        assertTrue(french.compare("afghani", "Caribbean") < 0)
        assertTrue("afghani" > "Caribbean", "The premise: a naive sort disagrees.")
    }

    @Test
    fun `the offered list is in collated name order`() {
        for (locale in listOf(AMERICAN, FRENCH)) {
            val names = Currencies.all(locale).map { it.name }

            assertEquals(names.sortedWith(Collator.getInstance(locale)), names, "Out of order in $locale.")
        }
    }

    @Test
    fun `the collated order differs from a naive one`() {
        val names = Currencies.all(FRENCH).map { it.name }

        assertNotEquals(names.sorted(), names)
    }

    @Test
    fun `the list is built once per locale`() {
        assertSame(Currencies.all(AMERICAN), Currencies.all(AMERICAN))
        assertNotEquals(Currencies.all(AMERICAN), Currencies.all(FRENCH))
    }
}
