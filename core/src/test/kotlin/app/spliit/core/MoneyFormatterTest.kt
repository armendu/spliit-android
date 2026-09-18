package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Every test here passes an explicit [Locale]. A formatting test that reads the machine's
 * default locale passes here and fails on a runner set to another region — or, worse, passes on
 * both for different reasons. The default locale is never consulted in this file.
 */
private val AMERICAN = Locale.of("en", "US")
private val FRENCH = Locale.of("fr", "FR")
private val GERMAN = Locale.of("de", "DE")
private val JAPANESE = Locale.of("ja", "JP")

/**
 * The JDK separates groups with a narrow no-break space in some locales and precedes the
 * currency symbol with a no-break space in others, and which one it picks is CLDR's business and
 * free to change. Assertions compare the parts that carry meaning, with every flavour of space
 * flattened to a plain one.
 */
private fun String.spacesFlattened(): String =
    replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2007', ' ')

class MoneyFormatterTest {

    // ---- minor-unit precision ---------------------------------------------------------

    @Test
    fun `a two-decimal currency keeps two digits`() {
        assertEquals(2, MoneyFormatter.minorUnitDigits("USD"))
        assertEquals(2, MoneyFormatter.minorUnitDigits("EUR"))
        assertEquals(2, MoneyFormatter.minorUnitDigits("GBP"))
    }

    @Test
    fun `the yen has no minor unit at all`() {
        assertEquals(0, MoneyFormatter.minorUnitDigits("JPY"))
        assertEquals(0, MoneyFormatter.minorUnitDigits("ISK"))
    }

    @Test
    fun `the gulf dinars have three`() {
        assertEquals(3, MoneyFormatter.minorUnitDigits("KWD"))
        assertEquals(3, MoneyFormatter.minorUnitDigits("BHD"))
        assertEquals(3, MoneyFormatter.minorUnitDigits("OMR"))
    }

    /** A group predating the ISO code field stored hundredths, so that is what it is read as. */
    @Test
    fun `a group with only a symbol and no code is hundredths`() {
        assertEquals(2, MoneyFormatter.minorUnitDigits(null))
    }

    /**
     * Instances are self-hosted and the group's currency is free text, so anything at all can
     * arrive here. `Currency.getInstance` answers junk with an exception, which must not reach a
     * screen that is only trying to draw a number.
     */
    @Test
    fun `an unknown code falls back instead of throwing`() {
        assertEquals(2, MoneyFormatter.minorUnitDigits("ZZZ"))
        assertEquals(2, MoneyFormatter.minorUnitDigits("BITCOIN"))
        assertEquals(2, MoneyFormatter.minorUnitDigits(""))
        assertEquals(2, MoneyFormatter.minorUnitDigits("   "))
        assertEquals(2, MoneyFormatter.minorUnitDigits("US"))
        assertEquals(2, MoneyFormatter.minorUnitDigits("$"))
    }

    /**
     * Pinned against the JDK rather than assumed: gold and special drawing rights are real ISO
     * codes with no minor unit defined, and the JDK says so with -1. Passing that through would
     * give a formatter negative precision, which throws at format time rather than here.
     */
    @Test
    fun `a pseudo-currency with no defined precision falls back rather than going negative`() {
        assertEquals(
            -1,
            java.util.Currency.getInstance("XAU").defaultFractionDigits,
            "The premise of this test: the JDK reports -1 for gold.",
        )

        assertEquals(2, MoneyFormatter.minorUnitDigits("XAU"))
        assertEquals(2, MoneyFormatter.minorUnitDigits("XDR"))
        assertEquals(2, MoneyFormatter.minorUnitDigits("XTS"))
    }

    /** Stored codes have arrived lower-case and padded; both name the same currency. */
    @Test
    fun `a code is read however it was stored`() {
        assertEquals(0, MoneyFormatter.minorUnitDigits("jpy"))
        assertEquals(3, MoneyFormatter.minorUnitDigits(" KWD "))
    }

    // ---- formatting -------------------------------------------------------------------

    @Test
    fun `minor units are rendered with the group's symbol`() {
        val formatter = MoneyFormatter("$", "USD", AMERICAN)

        assertEquals("$12.34", formatter.format(1234))
        assertEquals("$0.00", formatter.format(0))
        assertEquals("$1.00", formatter.format(100))
        assertEquals("$1.05", formatter.format(105))
    }

    /**
     * The one that costs real money. `1234` in a yen group is ¥1,234 — a hundred times what a
     * `/ 100` would draw, and the same record would then read differently on every client.
     */
    @Test
    fun `a currency with no minor unit is not divided by a hundred`() {
        val yen = MoneyFormatter("¥", "JPY", AMERICAN)

        assertEquals(0, yen.minorUnitDigits)
        assertEquals("¥1,234", yen.format(1234))
        assertNotEquals("¥12.34", yen.format(1234))
        assertEquals("1234", yen.formatPlain(1234))
    }

    @Test
    fun `a currency with three decimal places keeps all three`() {
        val dinar = MoneyFormatter("KD", "KWD", AMERICAN)

        assertEquals(3, dinar.minorUnitDigits)
        assertEquals("KD1.234", dinar.format(1234))
        assertEquals("1.234", dinar.formatPlain(1234))
    }

    /**
     * The trap the JDK sets: `DecimalFormat.setCurrency` is documented not to touch the fraction
     * digits, so a formatter built for a Japanese user keeps ja-JP's zero digits and would draw
     * a dollar amount as "$12". The precision belongs to the currency, not to the reader.
     */
    @Test
    fun `the currency decides precision even when the reader's locale has its own`() {
        val dollarsForAJapaneseReader = MoneyFormatter("$", "USD", JAPANESE)

        assertEquals(2, dollarsForAJapaneseReader.minorUnitDigits)
        assertEquals("$12.34", dollarsForAJapaneseReader.format(1234))
    }

    /**
     * The web app formatted everything as euros in en-US and swapped the sign, which put every
     * user on American conventions. Separators and placement are the reader's; the symbol is the
     * group's. Both sides asserted explicitly so no machine default can decide the outcome.
     */
    @Test
    fun `separators and symbol placement follow the reader's locale`() {
        val american = MoneyFormatter("€", "EUR", AMERICAN).format(123_456)
        val french = MoneyFormatter("€", "EUR", FRENCH).format(123_456)

        assertEquals("€1,234.56", american)
        assertEquals("1 234,56 €", french.spacesFlattened())
    }

    @Test
    fun `a german reader gets german separators`() {
        assertEquals("1.234,56 $", MoneyFormatter("$", "USD", GERMAN).format(123_456).spacesFlattened())
    }

    @Test
    fun `a group with only a symbol still formats, in hundredths`() {
        val formatter = MoneyFormatter("kr", null, AMERICAN)

        assertEquals(2, formatter.minorUnitDigits)
        assertEquals("kr42.50", formatter.format(4250))
        assertEquals("12.34", formatter.formatPlain(1234))
    }

    /**
     * A code the JDK cannot name is still what the group is denominated in, so it is shown
     * rather than silently replaced by the reader's own currency symbol — which is what the
     * locale's formatter would otherwise supply, and would be a plausible-looking lie.
     */
    @Test
    fun `an unknown code is shown rather than replaced by the reader's currency`() {
        val formatted = MoneyFormatter("", "BITCOIN", AMERICAN).format(1234).spacesFlattened()

        assertTrue(formatted.contains("12.34"), formatted)
        assertTrue(formatted.contains("BITCOIN"), formatted)
        assertFalse(formatted.contains("$"), formatted)
    }

    @Test
    fun `a negative amount keeps its sign`() {
        val formatted = MoneyFormatter("$", "USD", AMERICAN).format(-7797)

        assertTrue(formatted.contains("77.97"), formatted)
        assertTrue(formatted.contains("-"), formatted)
    }

    @Test
    fun `the plain form drops the symbol and the grouping, for input fields`() {
        assertEquals("1234.56", MoneyFormatter("$", "USD", AMERICAN).formatPlain(123_456))
        assertEquals("1234,56", MoneyFormatter("$", "USD", FRENCH).formatPlain(123_456))
    }

    // ---- parsing ----------------------------------------------------------------------

    @Test
    fun `a typed decimal becomes minor units`() {
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("42.50", AMERICAN))
        assertEquals(4200L, MoneyFormatter.parseMinorUnits("42", AMERICAN))
        assertEquals(5L, MoneyFormatter.parseMinorUnits("0.05", AMERICAN))
        assertEquals(-1230L, MoneyFormatter.parseMinorUnits("-12.30", AMERICAN))
    }

    /**
     * A French keyboard produces a comma. Reading it as a thousands separator turns 12,50 into
     * 1250.00 — a hundredfold error from one character, and the web app shipped a fix for it.
     */
    @Test
    fun `the reader's decimal separator is understood, and so is a plain dot`() {
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("42,50", FRENCH))
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("42.50", FRENCH))
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("42,50", GERMAN))
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("42.50", GERMAN))
    }

    @Test
    fun `grouping separators are not mistaken for decimals`() {
        assertEquals(123_456L, MoneyFormatter.parseMinorUnits("1,234.56", AMERICAN))
        assertEquals(123_456L, MoneyFormatter.parseMinorUnits("1.234,56", GERMAN))
        assertEquals(123_456L, MoneyFormatter.parseMinorUnits("1 234,56", FRENCH))
        // Three digits behind it and nothing after: grouping, in the locale that groups that way.
        assertEquals(123_400L, MoneyFormatter.parseMinorUnits("1,234", AMERICAN))
        assertEquals(123_400L, MoneyFormatter.parseMinorUnits("1.234", GERMAN))
    }

    @Test
    fun `what is typed scales by the currency, not always by a hundred`() {
        assertEquals(1234L, MoneyFormatter.parseMinorUnits("1234", AMERICAN, minorUnitDigits = 0))
        assertEquals(123_400L, MoneyFormatter.parseMinorUnits("1234", AMERICAN, minorUnitDigits = 2))
        assertEquals(1234L, MoneyFormatter.parseMinorUnits("1.234", AMERICAN, minorUnitDigits = 3))

        // A yen group has nowhere to put a fraction, so one typed anyway is rounded away.
        assertEquals(1235L, MoneyFormatter.parseMinorUnits("1234.6", AMERICAN, minorUnitDigits = 0))
    }

    /**
     * Also the reason parsing goes through [java.math.BigDecimal] rather than a double: the
     * double path computes 1.005 * 100 as 100.49999999999999 and rounds it *down*, which is a
     * cent lost on a value a person typed exactly.
     */
    @Test
    fun `a digit past the currency's precision is rounded half-up, on a decimal path`() {
        assertEquals(101L, MoneyFormatter.parseMinorUnits("1.005", AMERICAN))
        assertEquals(100L, MoneyFormatter.parseMinorUnits("1.004", AMERICAN))
        assertEquals(-101L, MoneyFormatter.parseMinorUnits("-1.005", AMERICAN))
    }

    @Test
    fun `a pasted currency symbol is ignored`() {
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("$42.50", AMERICAN))
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("42.50 €", AMERICAN))
        assertEquals(4250L, MoneyFormatter.parseMinorUnits("42,50\u00A0€", FRENCH))
    }

    @Test
    fun `empty or nonsense input is null rather than zero`() {
        assertNull(MoneyFormatter.parseMinorUnits("", AMERICAN))
        assertNull(MoneyFormatter.parseMinorUnits("   ", AMERICAN))
        assertNull(MoneyFormatter.parseMinorUnits("abc", AMERICAN))
        assertNull(MoneyFormatter.parseMinorUnits("€", AMERICAN))
        assertNull(MoneyFormatter.parseMinorUnits("-", AMERICAN))
    }

    /** An amount field takes arbitrary digits; overflowing minor units is a null, not a crash. */
    @Test
    fun `a number too large for minor units is null rather than a crash`() {
        assertNull(MoneyFormatter.parseMinorUnits("999999999999999999999999", AMERICAN))
    }

    @Test
    fun `an instance parses at its own currency's precision`() {
        assertEquals(1234L, MoneyFormatter("¥", "JPY", AMERICAN).parse("1,234"))
        assertEquals(1234L, MoneyFormatter("KD", "KWD", AMERICAN).parse("1.234"))
        assertEquals(1234L, MoneyFormatter("$", "USD", AMERICAN).parse("12.34"))
    }

    // ---- round trip -------------------------------------------------------------------

    /**
     * Formatting and parsing are each other's inverse or the amount field silently edits the
     * number it was handed. Run across all three precisions and three locales, because the
     * failure modes differ: a grouping separator misread as a decimal point, or the reverse.
     */
    @Test
    fun `format then parse returns the original minor units`() {
        val amounts = listOf<Long>(0, 1, 5, 1234, -1234, 123_456, -7797, 123_456_789)

        for (locale in listOf(AMERICAN, FRENCH, GERMAN, JAPANESE)) {
            for (code in listOf("JPY", "USD", "KWD")) {
                val formatter = MoneyFormatter(currencyCode = code, locale = locale)
                for (amount in amounts) {
                    assertEquals(
                        amount,
                        formatter.parse(formatter.format(amount)),
                        "$code in $locale: ${formatter.format(amount)}",
                    )
                    assertEquals(
                        amount,
                        formatter.parse(formatter.formatPlain(amount)),
                        "$code in $locale, plain: ${formatter.formatPlain(amount)}",
                    )
                }
            }
        }
    }

    // ---- the one amount that is not an integer ----------------------------------------

    /**
     * `totalParticipantShare` is the only non-integer amount in the API: instances older than
     * the web app's *Shares* change sum floating-point thirds and round to two decimals, so
     * `1416.67` arrives where 1417 minor units are meant. It is rounded on the way to the
     * display and never on the way in, so nothing downstream inherits a rounded total.
     */
    @Test
    fun `a non-integer share rounds half-up on the way to the display`() {
        assertEquals(1417L, MoneyFormatter.roundMinorUnits(1416.67))
        assertEquals(1416L, MoneyFormatter.roundMinorUnits(1416.33))
        assertEquals(1417L, MoneyFormatter.roundMinorUnits(1416.5))
    }

    /**
     * Half-up, not half-even and not `Math.round`. `Math.round` is half-*up towards positive
     * infinity*, so it answers -2 for -2.5 — a rounding that moves a debt in the creditor's
     * favour. Half-even would answer 2 for 2.5, which is not what a person reading a receipt
     * expects either. Both are pinned here as the things this must not be.
     */
    @Test
    fun `half-up means away from zero, not towards positive infinity`() {
        assertEquals(3L, MoneyFormatter.roundMinorUnits(2.5))
        assertEquals(-3L, MoneyFormatter.roundMinorUnits(-2.5))
        assertEquals(2L, MoneyFormatter.roundMinorUnits(1.5))

        assertEquals(-2L, Math.round(-2.5), "The premise: Math.round is not this rounding.")
    }

    @Test
    fun `a share is formatted at the group's precision after rounding`() {
        assertEquals("€14.17", MoneyFormatter("€", "EUR", AMERICAN).formatShare(1416.67))
        assertEquals("¥1,417", MoneyFormatter("¥", "JPY", AMERICAN).formatShare(1416.67))
        assertEquals("1 417 ¥", MoneyFormatter("¥", "JPY", FRENCH).formatShare(1416.67).spacesFlattened())
    }
}
