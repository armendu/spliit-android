package app.spliit.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

// The write side, where "omitted is not cleared" lives.
//
// [SuperJson]'s encoder runs `explicitNulls = false` and `encodeDefaults = false`, because a key
// absent from a request is `undefined` to tRPC and Prisma skips the column — which is what lets a
// partial update leave everything else alone. Two consequences shape every declaration here, and
// both fail *silently*: the request is accepted, the unit tests are green, and the column keeps
// its old value.
//
//  1. **A Kotlin `null` is an omitted field, not a JSON null.** A field that must clear something
//     with a literal `null` is therefore typed [JsonElement] and given [JsonNull] — see
//     [ExpenseFormValues.originalCurrency], the only conversion field whose schema accepts one.
//  2. **A Kotlin default value is an omitted field too.** So nothing the server's schema requires
//     is declared with a default, however obvious the value looks. `FormValuesTest` pins the full
//     key set of a request to catch a convenience default being added later.
//
// What counts as "empty" is per field, and was established against a live instance rather than
// read off the schema:
//
// | field                             | null            | `""`            | omitted          |
// |-----------------------------------|-----------------|-----------------|------------------|
// | `groupFormValues.information`     | 400             | clears it       | leaves it        |
// | `groupFormValues.currencyCode`    | accepted (null) | clears it (web) | leaves it        |
// | `expenseFormValues.originalCurrency` | **clears it** | —               | leaves it        |
// | `expenseFormValues.originalAmount`   | **400**       | clears it       | leaves it        |
// | `expenseFormValues.conversionRate`   | **400**       | accepted        | leaves it        |

/**
 * The input `groups.create` and `groups.update` take.
 *
 * No field has a default: with `encodeDefaults = false` a default is an omission, and an omitted
 * field on an update is one the server leaves exactly as it was.
 */
@Serializable
public data class GroupFormValues(
    public val name: String,
    /**
     * The group's description. **`""` is how it is cleared** — the schema is a plain string and a
     * JSON null is a 400, verified against a live instance.
     */
    public val information: String,
    /** A free-text symbol such as "$" or "CHF". The server does not interpret it. */
    public val currency: String,
    /**
     * ISO-4217, or `""` for a group that has no code — which is what the web app writes when a
     * currency is dropped, and what clears the column. A Kotlin `null` here would omit the key
     * and leave the old code in place on an update.
     */
    public val currencyCode: String,
    public val participants: List<Participant>,
) {
    /**
     * A participant as the form sends one. An absent [id] is how the server is told to create a
     * participant rather than rename an existing one; leaving a participant out of the list
     * entirely is how one is removed.
     */
    @Serializable
    public data class Participant(
        public val id: String? = null,
        public val name: String,
    )
}

/**
 * The input the three `groups.expenses.*` mutations take.
 *
 * [expenseDate] is `@Contextual`, and that is load-bearing. Kotlin binds serializers at compile
 * time, so the per-call marker that produces superjson's `meta.values` reaches a field only
 * through the per-call `SerializersModule` — and only `@Contextual` consults it. Writing
 * `@Serializable(with = InstantSerializer::class)` instead compiles, decodes perfectly, leaves
 * every unit test green, and sends the date with **no annotation at all**; the server rebuilds
 * real `Date` instances from those annotations before its own validation runs.
 *
 * **Half of that is unverified, and it is the half that bites.** Sending the annotation was
 * confirmed end to end — our encoder's own bytes were replayed at a live instance, accepted, and
 * the date stored intact. The *rejection* was not: the e2e image's zod coerces a plain ISO string
 * and answers 200 with no `meta` block at all, so no server we have observed actually enforces
 * this. The annotation is held in place by `FormValuesTest` rather than by evidence, on the
 * strength of the iOS app having hit the failure against a server we have not reproduced here
 * (and which nobody should go looking for by writing to production spliit.app). If a write ever
 * starts answering 400 about a date, this is the first thing to look at rather than the last.
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
    /**
     * Sent, validated, and never read by any procedure — the web app keeps the saved split in
     * `localStorage` and ours lives on the recent-group row (Part 7). It is in the schema, so it
     * is in the request.
     */
    public val saveDefaultSplittingOptions: Boolean,
    public val isReimbursement: Boolean,
    public val documents: List<ExpenseDocument>,
    /** Omitted when null, which leaves whatever note the expense already had. */
    public val notes: String? = null,
    public val recurrenceRule: RecurrenceRule,
    /**
     * What was actually paid, in [originalCurrency]'s own minor units.
     *
     * Its schema is a union of a number, a numeric string and `''`, and it **answers 400 to a
     * null** — verified against a live instance. So conversion is dropped by clearing
     * [originalCurrency] alone and omitting this, which leaves the stored figure in place,
     * inert: [originalCurrency] is what says an expense was converted and nothing reads the
     * other two without it.
     */
    public val originalAmount: Int? = null,
    /**
     * ISO-4217 of what was actually paid, as [JsonPrimitive] — or [JsonNull] to say the expense
     * is in the group's own currency after all.
     *
     * **This is the one field in the API whose schema takes a null, and the only way to stop an
     * expense being converted.** It cannot be a Kotlin `String?`: `explicitNulls = false` would
     * omit the key, the server would read `undefined`, Prisma would skip the column, and an
     * expense moved back to the group's currency would go on claiming it was paid in another.
     *
     * A Kotlin `null` here is therefore *not* "no original currency" — it omits the field and
     * leaves the column alone, which is only ever what a deliberately partial update wants.
     * [conversionCurrency] is the safe way to spell both cases.
     */
    public val originalCurrency: JsonElement? = null,
    /**
     * `amount` ÷ `originalAmount`: one unit of [originalCurrency] in the group's currency.
     *
     * Sent as a string, which its schema accepts alongside a number — and, like
     * [originalAmount], **it answers 400 to a null**, so it is omitted rather than cleared.
     */
    public val conversionRate: LenientDecimal? = null,
) {
    @Serializable
    public data class PaidFor(
        /** Participant ID. */
        public val participant: String,
        /**
         * Stored verbatim, and meaning two different things depending on [splitMode]: the share
         * value ×100 for [SplitMode.EVENLY], [SplitMode.BY_SHARES] and
         * [SplitMode.BY_PERCENTAGE] — one share is `100`, 33.5% is `3350` — whatever the
         * currency; and a raw **minor-unit amount** for [SplitMode.BY_AMOUNT], where the entries
         * must sum to [amount]. Part 6 computes it; this carries it unaltered.
         */
        public val shares: Int,
    )

    public companion object {
        /**
         * [originalCurrency] for an expense paid in [code], or the explicit null that clears the
         * conversion when [code] is null.
         *
         * The point of the helper is that the null-ish case has to reach the wire *as* a null:
         * writing `code?.let(::JsonPrimitive)` and stopping there gives a Kotlin null, which is
         * omitted.
         */
        public fun conversionCurrency(code: String?): JsonElement =
            if (code == null) JsonNull else JsonPrimitive(code)
    }
}
