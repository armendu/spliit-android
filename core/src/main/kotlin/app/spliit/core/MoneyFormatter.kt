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

    public fun format(minorUnits: Long): String {
        val text = synchronized(currencyFormat) { currencyFormat.format(asDecimal(minorUnits)) }
        return if (hasNoSymbol) text.trim { Character.isSpaceChar(it) || it.isWhitespace() } else text
    }

    // Nothing to draw with: the reader's own currency would be a plausible-looking wrong answer.
    private val hasNoSymbol: Boolean =
        currencySymbol.isBlank() && currencyCode.isNullOrBlank()

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
            else -> currencyCode?.trim()?.takeIf { it.isNotEmpty() } ?: ""
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
            val decimals = setOf(symbols.decimalSeparator, symbols.monetaryDecimalSeparator, ARABIC_DECIMAL)
            val groupings = setOf(symbols.groupingSeparator, ARABIC_GROUPING)
            val digits = StringBuilder()
            val separators = ArrayList<Separator>()
            var negative = false
            var trailingText = false

            for (character in text) {
                val digit = Character.digit(character, 10)
                val isSeparator = character in decimals || character in groupings || character == '.' || character == ','
                // A currency may lead or trail ("KWD 1.000", "12,50 kr."); a letter inside the number ("1e5") may not.
                if (trailingText) {
                    if (digit >= 0) return null
                    continue
                }
                when {
                    digit >= 0 -> digits.append('0' + digit)
                    isSeparator -> separators += Separator(digits.length, character)
                    character == '-' || character == '\u2212' ->
                        if (digits.isEmpty() && !negative) negative = true else return null
                    Character.isLetter(character) && digits.isNotEmpty() -> trailingText = true
                }
            }
            if (digits.isEmpty()) return null

            val decimalAt = decimalPosition(separators, digits.length, decimals, groupings) ?: return null
            val scale = if (decimalAt < 0) 0 else digits.length - decimalAt
            val value = BigDecimal(BigInteger(digits.toString()), scale)
            return if (negative) value.negate() else value
        }

        // The digit index the decimal point sits before, -1 for a whole number, null when the separators
        // can't be read either way. With two kinds of separator the last one is the decimal point, whatever
        // the locale, so a receipt's "1,234.56" pasted on a German phone isn't read as 1.23456.
        private fun decimalPosition(
            separators: List<Separator>,
            digitCount: Int,
            decimals: Set<Char>,
            groupings: Set<Char>,
        ): Int? {
            val last = separators.lastOrNull() ?: return -1
            val kinds = separators.map { it.character }.toSet()
            val earlier = separators.dropLast(1)

            if (kinds.size > 1) {
                if (earlier.any { it.character == last.character }) return null
                return if (isGrouping(earlier, last.at)) last.at else null
            }
            if (earlier.isNotEmpty()) return if (isGrouping(separators, digitCount)) -1 else null

            val groupShaped = digitCount - last.at == 3
            return when {
                last.character in decimals -> last.at
                last.character in groupings && groupShaped -> -1
                else -> last.at
            }
        }

        // Groups of three ending where the integer part does, or India's lakh grouping (1,23,45,678): pairs, then three.
        private fun isGrouping(separators: List<Separator>, integerEnd: Int): Boolean {
            if (separators.isEmpty()) return true
            if (separators.first().at !in 1..3) return false
            val sizes = (separators.map { it.at } + integerEnd).zipWithNext { from, to -> to - from }
            return sizes.last() == 3 && (sizes.all { it == 3 } || sizes.dropLast(1).all { it == 2 })
        }

        private class Separator(val at: Int, val character: Char)

        private const val ARABIC_DECIMAL = '\u066B'
        private const val ARABIC_GROUPING = '\u066C'
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
