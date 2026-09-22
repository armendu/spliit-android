package app.spliit.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

// The write side. Omitted is not cleared.
//
// [SuperJson] encodes with `explicitNulls = false` and `encodeDefaults = false`, so a Kotlin
// null and a Kotlin default both mean "key absent", and an absent key leaves the column alone.
// Nothing the server requires gets a default; `FormValuesTest` pins the full key set.
//
// To send a literal JSON null, the field is a [JsonElement] holding [JsonNull]. Only
// [ExpenseFormValues.originalCurrency] needs that.
//
// What "empty" means, per field, measured against a live instance:
//
// | field                                | null      | `""`      | omitted   |
// |--------------------------------------|-----------|-----------|-----------|
// | `groupFormValues.information`        | 400       | clears    | leaves    |
// | `groupFormValues.currencyCode`       | accepted  | clears    | leaves    |
// | `expenseFormValues.originalCurrency` | clears    | n/a       | leaves    |
// | `expenseFormValues.originalAmount`   | 400       | clears    | leaves    |
// | `expenseFormValues.conversionRate`   | 400       | accepted  | leaves    |

/** The input `groups.create` and `groups.update` take. No defaults: see the note above. */
@Serializable
public data class GroupFormValues(
    public val name: String,
    /** The description. `""` clears it; a JSON null is a 400. */
    public val information: String,
    /** A free-text symbol such as "$" or "CHF". The server does not interpret it. */
    public val currency: String,
    /** ISO-4217, or `""` to clear it. Not nullable: a null would omit the key and keep the
     *  old code on an update. */
    public val currencyCode: String,
    public val participants: List<Participant>,
) {
    /** No [id] creates a participant; leaving one out of the list removes it. */
    @Serializable
    public data class Participant(
        public val id: String? = null,
        public val name: String,
    )
}

/**
 * The input the three `groups.expenses.*` mutations take.
 *
 * [expenseDate] must stay `@Contextual`. Kotlin binds serializers at compile time, so the
 * per-call marker that produces superjson's `meta.values` only reaches a field through the
 * per-call `SerializersModule`, and only `@Contextual` consults it. Annotating the field
 * directly compiles, decodes fine, and sends the date with no annotation at all.
 *
 * Caveat: sending the annotation is verified, its necessity is not. No server we have tested
 * rejects a plain ISO string, so `FormValuesTest` is what holds this in place, on the strength
 * of the iOS app having hit the failure elsewhere. If a write starts answering 400 about a
 * date, look here first.
 */
@Serializable
public data class ExpenseFormValues(
    public val title: String,
    @Contextual public val expenseDate: Instant,
    /** Minor units, in the group's currency. The server writes this straight to the database. */
    public val amount: Int,
    /** [ExpenseCategory] ID; 0 is "General". */
    public val category: Int,
    /** Participant ID of whoever paid. */
    public val paidBy: String,
    public val paidFor: List<PaidFor>,
    public val splitMode: SplitMode,
    /** Sent, validated, never read by any procedure. In the schema, so in the request. */
    public val saveDefaultSplittingOptions: Boolean,
    public val isReimbursement: Boolean,
    public val documents: List<ExpenseDocument>,
    /** Omitted when null, which leaves whatever note the expense already had. */
    public val notes: String? = null,
    public val recurrenceRule: RecurrenceRule,
    /**
     * What was actually paid, in [originalCurrency]'s minor units. Answers 400 to a null, so
     * conversion is dropped by clearing [originalCurrency] and omitting this. The stale figure
     * left behind is inert: nothing reads it without a currency.
     */
    public val originalAmount: Int? = null,
    /**
     * ISO-4217 of what was paid, or [JsonNull] to stop the expense being converted. The one
     * field in the API whose schema takes a null, which is why it is not a `String?`: that
     * would omit the key and leave the expense claiming a currency it no longer has.
     *
     * Use [conversionCurrency]; a Kotlin null here means "leave the column alone".
     */
    public val originalCurrency: JsonElement? = null,
    /** `amount` / `originalAmount`, sent as a string. Answers 400 to a null, so it is
     *  omitted rather than cleared. */
    public val conversionRate: LenientDecimal? = null,
) {
    @Serializable
    public data class PaidFor(
        /** Participant ID. */
        public val participant: String,
        /**
         * Two units in one field, decided by [splitMode]. For EVENLY, BY_SHARES and
         * BY_PERCENTAGE it is the share value x100 (one share is `100`, 33.5% is `3350`),
         * whatever the currency. For BY_AMOUNT it is raw minor units summing to [amount].
         */
        public val shares: Int,
    )

    public companion object {
        /**
         * [originalCurrency] for an expense paid in [code], or the explicit null that clears
         * the conversion. Exists because `code?.let(::JsonPrimitive)` gives a Kotlin null,
         * which is omitted rather than sent.
         */
        public fun conversionCurrency(code: String?): JsonElement =
            if (code == null) JsonNull else JsonPrimitive(code)
    }
}
