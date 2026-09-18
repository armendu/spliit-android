package app.spliit.core

import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.Currency as IsoCurrency

/**
 * An ISO 4217 currency, as the JDK already knows it.
 *
 * The list, the names, the symbols and the precision all come from the platform, so the picker
 * is translated wherever Android is and there is **no currency table in this repo to keep up to
 * date**. The web app ships a generated JSON for exactly this and has to regenerate it; the
 * iOS app deliberately keeps none, and neither do we — a 159-row file of translated names is
 * precisely the kind of thing that rots between releases while looking fine.
 */
public data class Currency(
    /** ISO 4217, upper case — "CHF". */
    public val code: String,
    /** Localised for the reader — "Swiss Franc", "franc suisse". */
    public val name: String,
    /**
     * What amounts are drawn with — "CHF", "$", "kr". This is what gets stored as the group's
     * free-text `currency`, so it follows the reader's locale the way the web app's does: a
     * French reader picks "$US" for the dollar where an American picks "$".
     */
    public val symbol: String,
    /**
     * Digits in the minor unit: two nearly everywhere, none for the yen, three for the dinars.
     * Never negative — see [MoneyFormatter.minorUnitDigits].
     */
    public val minorUnitDigits: Int,
)

/**
 * Orders text the way the reader's language orders words, rather than the way Unicode numbers
 * its code points.
 *
 * `String.compareTo` compares code points, which puts every accented initial after every plain
 * one and every lower-case word after every upper-case one: a French reader sorting that way
 * finds "Épicerie" below "Vêtements", and one capitalised name stranded above a list that is
 * otherwise alphabetical. Anything a person reads down — the currency picker, a participant
 * list — is sorted with this.
 */
public fun localizedOrder(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale)
    // A Collator carries iteration state, so one shared across threads can answer wrongly rather
    // than merely slowly. Guarded rather than rebuilt per comparison: building one is the
    // expensive part, and a sort of a few hundred names is not a contended path.
    return Comparator { left, right -> synchronized(collator) { collator.compare(left, right) } }
}

/** The currencies the picker offers, and the lookup from a stored code to one of them. */
public object Currencies {

    // A form holding the picker rebuilds its list on every recomposition — on every keystroke in
    // the field above it — and walking the ISO tables and collating 155 names costs milliseconds
    // each time. Keyed by language tag rather than by Locale so that two equal locales share.
    private val lists = ConcurrentHashMap<String, List<Currency>>()

    /**
     * Every currency a country is currently counted in, named and ordered for the reader.
     *
     * Derived from the countries rather than from `Currency.getAvailableCurrencies()`, which is
     * the wrong list twice over: it carries currencies no longer in use, and pseudo-currencies
     * that are not money at all — gold, palladium, special drawing rights, the test code XTS.
     * A picker offering someone "Gold" as their group's currency is offering a mistake.
     */
    public fun all(locale: Locale = Locale.getDefault()): List<Currency> =
        lists.computeIfAbsent(locale.toLanguageTag()) { build(locale) }

    /**
     * One currency by code, or null if the JDK cannot name one — an instance that stored
     * something which is not a currency must not be presented as one.
     *
     * Not restricted to [all]: a group is free to hold a code outside the offered list, and a
     * code we decline to offer is still a code we have to draw.
     */
    public fun named(code: String, locale: Locale = Locale.getDefault()): Currency? =
        isoCurrency(code)?.toCurrency(locale)

    private fun build(locale: Locale): List<Currency> =
        Locale.getISOCountries()
            .mapNotNull { country ->
                // Null for a territory with no currency of its own, and an exception for one the
                // JDK does not recognise; neither is a currency to offer.
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
