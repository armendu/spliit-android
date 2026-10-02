package app.spliit.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import java.util.Currency as IsoCurrency

// Minor units aren't always hundredths (yen has none, dinars three). Never divide by 100.
public class MoneyFormatter(
    public val currencySymbol: String = "",
    public val currencyCode: String? = null,
    public val locale: Locale = Locale.getDefault(),
) {
    public val minorUnitDigits: Int = minorUnitDigits(currencyCode)

    // java.text formatters aren't thread-safe: built once, then guarded.
    private val currencyFormat: DecimalFormat by lazy { buildCurrencyFormat() }
    private val plainFormat: DecimalFormat by lazy { buildPlainFormat() }

    public fun format(minorUnits: Long): String =
        synchronized(currencyFormat) { currencyFormat.format(asDecimal(minorUnits)) }

    public fun formatPlain(minorUnits: Long): String =
        synchronized(plainFormat) { plainFormat.format(asDecimal(minorUnits)) }

    public fun parse(text: String): Long? = parseMinorUnits(text, locale, minorUnitDigits)

    private fun asDecimal(minorUnits: Long): BigDecimal =
        BigDecimal.valueOf(minorUnits, minorUnitDigits)

    private fun buildCurrencyFormat(): DecimalFormat {
        val format = NumberFormat.getCurrencyInstance(locale) as DecimalFormat
        val currency = isoCurrency(currencyCode)

        // Before the symbol override: setting the currency resets the symbol.
        if (currency != null) format.currency = currency

        val symbol = when {
            currencySymbol.isNotBlank() -> currencySymbol
            currency != null -> null
            else -> currencyCode?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (symbol != null) {
            val symbols: DecimalFormatSymbols = format.decimalFormatSymbols
            symbols.currencySymbol = symbol
            format.decimalFormatSymbols = symbols
        }

        // Set explicitly: DecimalFormat.setCurrency doesn't change fraction digits.
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
        public const val DEFAULT_MINOR_UNIT_DIGITS: Int = 2

        public fun minorUnitDigits(currencyCode: String?): Int {
            val digits = isoCurrency(currencyCode)?.defaultFractionDigits ?: return DEFAULT_MINOR_UNIT_DIGITS
            // -1 means no minor unit defined (XAU, XDR, XTS); a negative precision would throw later.
            return if (digits < 0) DEFAULT_MINOR_UNIT_DIGITS else digits
        }

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
                null
            }
        }

        // Half-up away from zero. Not Math.round, which gives -2 for -2.5.
        public fun roundMinorUnits(amount: Double): Long =
            BigDecimal.valueOf(amount).setScale(0, RoundingMode.HALF_UP).toLong()

        public fun parseDecimal(text: String, locale: Locale = Locale.getDefault()): BigDecimal? {
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val digits = StringBuilder()
            val separators = ArrayList<Separator>()
            var negative = false

            for (character in text) {
                val digit = Character.digit(character, 10)
                when {
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

// `currency` is free text ("kr", ""), and Currency.getInstance throws rather than returning null.
internal fun isoCurrency(code: String?): IsoCurrency? {
    val normalised = code?.trim()?.uppercase(Locale.ROOT) ?: return null
    if (normalised.length != 3) return null
    return try {
        IsoCurrency.getInstance(normalised)
    } catch (_: IllegalArgumentException) {
        null
    }
}
