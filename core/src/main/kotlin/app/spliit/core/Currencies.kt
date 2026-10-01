package app.spliit.core

import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.Currency as IsoCurrency

public data class Currency(
    public val code: String,
    public val name: String,
    public val symbol: String,
    public val minorUnitDigits: Int,
)

public fun localizedOrder(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale)
    // Collator isn't thread-safe; guarded rather than rebuilt per comparison.
    return Comparator { left, right -> synchronized(collator) { collator.compare(left, right) } }
}

public object Currencies {
    private val lists = ConcurrentHashMap<String, List<Currency>>()

    // From countries, not getAvailableCurrencies(), which includes retired codes, metals and XTS.
    public fun all(locale: Locale = Locale.getDefault()): List<Currency> =
        lists.computeIfAbsent(locale.toLanguageTag()) { build(locale) }

    public fun named(code: String, locale: Locale = Locale.getDefault()): Currency? =
        isoCurrency(code)?.toCurrency(locale)

    private fun build(locale: Locale): List<Currency> =
        Locale.getISOCountries()
            .mapNotNull { country ->
                runCatching { IsoCurrency.getInstance(Locale.of("", country)) }.getOrNull()
            }
            .distinctBy { it.currencyCode }
            .map { it.toCurrency(locale) }
            .sortedWith(compareBy(localizedOrder(locale)) { it.name })

    private fun IsoCurrency.toCurrency(locale: Locale): Currency = Currency(
        code = currencyCode,
        name = getDisplayName(locale),
        symbol = getSymbol(locale),
        minorUnitDigits = MoneyFormatter.minorUnitDigits(currencyCode),
    )
}
