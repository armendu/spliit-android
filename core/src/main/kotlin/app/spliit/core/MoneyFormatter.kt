package app.spliit.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import java.util.Currency as IsoCurrency

/**
 * Formats the integer minor units the API deals in.
 *
 * Minor units are not always hundredths. `1234` is 12.34 in a two-decimal currency, ¥1,234 in
 * yen, and 1.234 in a Gulf dinar. Sixteen of the currencies offered have no minor unit and seven
 * have three, so `/ 100` anywhere here is a number wrong by 100x or 1000x on a screen that looks
 * entirely plausible. Ask [minorUnitDigits].
 *
 * A group's `currency` is free text ("$", "CHF", "kr"); only newer groups carry an ISO code. The
 * code decides precision, the symbol decides what is drawn, and the reader's locale decides
 * grouping and placement, so a French reader gets `1 234,56 €` and an American `$1,234.56` for
 * the same stored integer.
 *
 * Minor units are [Long] though the wire carries [Int]: a sum overflows 32 bits in a small-unit
 * currency well before the figure is unreasonable, and an overflow is a wrong number, not an
 * error.
 */
public class MoneyFormatter(
    /** What amounts are drawn with, the group's free-text `currency`. */
    public val currencySymbol: String = "",
    /** ISO 4217, when the group has one. Null for a group that predates the field. */
    public val currencyCode: String? = null,
    public val locale: Locale = Locale.getDefault(),
) {
    /** How many digits the stored integer keeps behind the decimal point. */
    public val minorUnitDigits: Int = minorUnitDigits(currencyCode)

    // java.text formatters are mutable and explicitly not thread-safe, unlike Foundation's,
    // which the iOS app can share freely. Built once because building one is the expensive part,
    // and guarded because a MoneyFormatter is held by a ViewModel and read from whichever thread
    // composes.
    private val currencyFormat: DecimalFormat by lazy { buildCurrencyFormat() }
    private val plainFormat: DecimalFormat by lazy { buildPlainFormat() }

    /**
     * @param minorUnits the value as stored: `1234` is 12.34 where there are two decimal places,
     *   and ¥1,234 where there are none.
     */
    public fun format(minorUnits: Long): String =
        synchronized(currencyFormat) { currencyFormat.format(asDecimal(minorUnits)) }

    /** The amount without its symbol or grouping, for input fields and axis labels. */
    public fun formatPlain(minorUnits: Long): String =
        synchronized(plainFormat) { plainFormat.format(asDecimal(minorUnits)) }

    /**
     * Formats an amount that arrived as a fraction of a minor unit, which
     * `totalParticipantShare` does on instances predating the *Shares* change. Rounding belongs
     * here, not at the boundary, which would hand later calculations a value never sent.
     */
    public fun formatShare(share: Double): String = format(roundMinorUnits(share))

    /** Parses what someone typed back into minor units at this currency's precision. */
    public fun parse(text: String): Long? = parseMinorUnits(text, locale, minorUnitDigits)

    private fun asDecimal(minorUnits: Long): BigDecimal =
        BigDecimal.valueOf(minorUnits, minorUnitDigits)

    private fun buildCurrencyFormat(): DecimalFormat {
        val format = NumberFormat.getCurrencyInstance(locale) as DecimalFormat
        val currency = isoCurrency(currencyCode)

        // Before the symbol override, not after: setting the currency also resets the symbol.
        if (currency != null) format.currency = currency

        val symbol = when {
            currencySymbol.isNotBlank() -> currencySymbol
            // No symbol stored, but the JDK knows the code, let it supply its own, which is
            // localised: a French reader gets "$US" for the dollar where an American gets "$".
            currency != null -> null
            // Neither a symbol nor a code the JDK knows. Showing the reader's own currency
            // symbol, which is what the untouched locale formatter would draw, would be a
            // plausible-looking lie, so the stored text is shown instead.
            else -> currencyCode?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (symbol != null) {
            val symbols: DecimalFormatSymbols = format.decimalFormatSymbols
            symbols.currencySymbol = symbol
            format.decimalFormatSymbols = symbols
        }

        // Set last, and never left to the formatter: `DecimalFormat.setCurrency` is documented
        // not to touch the fraction digits, so these are still the *reader's* locale's, zero in
        // ja-JP, which would draw a dollar amount as "$12". Precision belongs to the currency.
        format.minimumFractionDigits = minorUnitDigits
        format.maximumFractionDigits = minorUnitDigits
        format.roundingMode = RoundingMode.HALF_UP
        return format
    }

    private fun buildPlainFormat(): DecimalFormat {
        val format = NumberFormat.getNumberInstance(locale) as DecimalFormat
        format.isGroupingUsed = false
        format.minimumFractionDigits = minorUnitDigits
        format.maximumFractionDigits = minorUnitDigits
        format.roundingMode = RoundingMode.HALF_UP
        return format
    }

    public companion object {
        /**
         * What an amount is counted in when nothing says otherwise. Two, for two reasons: a
         * group with only a symbol was stored as hundredths by the web app, and an unresolvable
         * code is likelier to be counted like the majority than like the yen.
         */
        public const val DEFAULT_MINOR_UNIT_DIGITS: Int = 2

        /**
         * Decimal places for an ISO 4217 code: 2 for most, 0 for yen, 3 for Gulf dinars. Never
         * throws, because the group's currency is free text and `Currency.getInstance` rejects
         * anything that is not three known letters.
         */
        public fun minorUnitDigits(currencyCode: String?): Int {
            val digits = isoCurrency(currencyCode)?.defaultFractionDigits ?: return DEFAULT_MINOR_UNIT_DIGITS
            // -1 is the JDK's answer for a currency with no minor unit *defined*, gold (XAU),
            // special drawing rights (XDR), the test code XTS. Passed on, it would configure a
            // formatter with negative precision, which throws where it is used rather than here.
            return if (digits < 0) DEFAULT_MINOR_UNIT_DIGITS else digits
        }

        /**
         * Parses what someone typed into minor units, rounding half-up at the last digit the
         * currency has room for.
         *
         * @param minorUnitDigits what to scale by, from the group's currency. The default suits
         *   a share count or a percentage, which the protocol scales by 100 whatever the group
         *   is denominated in.
         */
        public fun parseMinorUnits(
            text: String,
            locale: Locale = Locale.getDefault(),
            minorUnitDigits: Int = DEFAULT_MINOR_UNIT_DIGITS,
        ): Long? {
            val value = parseDecimal(text, locale) ?: return null
            return try {
                value.movePointRight(minorUnitDigits.coerceAtLeast(0))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
            } catch (_: ArithmeticException) {
                // More digits than an amount can hold. An input field takes anything.
                null
            }
        }

        /**
         * Rounds a fractional minor unit, half-up meaning *away from zero*. Not `Math.round`,
         * which answers -2 for -2.5 and so moves a debt in one party's favour, and not
         * half-even. Through [BigDecimal], so what is rounded is the decimal the server sent.
         */
        public fun roundMinorUnits(amount: Double): Long =
            BigDecimal.valueOf(amount).setScale(0, RoundingMode.HALF_UP).toLong()

        /**
         * The number in typed or pasted text, as a decimal. Public because a conversion rate is
         * not money: rounding one to the group's precision would turn 0.9241 into 0.92.
         *
         * Anything that is not a digit, separator or leading minus is dropped. The hard part is
         * that `,` is the decimal point in Paris and the thousands separator in New York, and
         * keyboards disagree with locales: reading "42,50" as 4250 is a hundredfold error.
         *
         * So a separator the locale spells decimals with is a decimal point. Otherwise the last
         * separator is one *unless* it looks like a group (exactly three digits after, nothing
         * else) and either the locale groups with it or there is more than one.
         */
        public fun parseDecimal(text: String, locale: Locale = Locale.getDefault()): BigDecimal? {
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val digits = StringBuilder()
            // Where each separator falls, counted in digits seen so far, and what kind it is.
            val separators = ArrayList<Separator>()
            var negative = false

            for (character in text) {
                val digit = Character.digit(character, 10)
                when {
                    // Any script's digits, normalised: a keyboard may produce Arabic-Indic ones.
                    digit >= 0 -> digits.append('0' + digit)
                    character == symbols.decimalSeparator ||
                        character == symbols.monetaryDecimalSeparator ->
                        separators += Separator(digits.length, SeparatorKind.DECIMAL)
                    character == symbols.groupingSeparator ->
                        separators += Separator(digits.length, SeparatorKind.GROUPING)
                    character == '.' || character == ',' ->
                        separators += Separator(digits.length, SeparatorKind.EITHER)
                    (character == '-' || character == '\u2212') && digits.isEmpty() ->
                        negative = true
                }
            }
            if (digits.isEmpty()) return null

            val decimalAt = decimalPosition(separators, digits.length)
            val scale = if (decimalAt == null) 0 else digits.length - decimalAt
            val value = BigDecimal(BigInteger(digits.toString()), scale)
            return if (negative) value.negate() else value
        }

        private fun decimalPosition(separators: List<Separator>, digitCount: Int): Int? {
            val spelledDecimal = separators.lastOrNull { it.kind == SeparatorKind.DECIMAL }
            if (spelledDecimal != null) return spelledDecimal.at

            val last = separators.lastOrNull() ?: return null
            val groupShaped = digitCount - last.at == 3
            val groups = groupShaped &&
                (last.kind == SeparatorKind.GROUPING || separators.size > 1)
            return if (groups) null else last.at
        }

        private class Separator(val at: Int, val kind: SeparatorKind)

        private enum class SeparatorKind { DECIMAL, GROUPING, EITHER }
    }
}

/**
 * The JDK's currency for a code, or null. The single place that knows `currency` is free text:
 * a self-hosted Spliit stores "kr", "BITCOIN" or "" happily, and `Currency.getInstance` throws
 * on all three rather than returning null.
 */
internal fun isoCurrency(code: String?): IsoCurrency? {
    // Locale.ROOT, not the default: uppercasing "iqd" in Turkish gives "İQD", and the lookup
    // then fails for a currency that is perfectly real.
    val normalised = code?.trim()?.uppercase(Locale.ROOT) ?: return null
    if (normalised.length != 3) return null
    return try {
        IsoCurrency.getInstance(normalised)
    } catch (_: IllegalArgumentException) {
        null
    }
}
