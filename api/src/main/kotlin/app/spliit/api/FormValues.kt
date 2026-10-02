package app.spliit.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

// Omitted is not cleared: SuperJson drops nulls and defaults, and an absent key leaves the column
// alone. A literal null needs JsonNull. "Empty", per field, measured on a live instance:
// | field                                | null      | `""`      | omitted   |
// |--------------------------------------|-----------|-----------|-----------|
// | `groupFormValues.information`        | 400       | clears    | leaves    |
// | `groupFormValues.currencyCode`       | accepted  | clears    | leaves    |
// | `expenseFormValues.originalCurrency` | clears    | n/a       | leaves    |
// | `expenseFormValues.originalAmount`   | 400       | clears    | leaves    |
// | `expenseFormValues.conversionRate`   | 400       | accepted  | leaves    |

@Serializable
public data class GroupFormValues(
    public val name: String,
    public val information: String,
    public val currency: String,
    // Not nullable: null would omit the key and keep the old code.
    public val currencyCode: String,
    public val participants: List<Participant>,
) {
    @Serializable
    public data class Participant(
        public val id: String? = null,
        public val name: String,
    )
}

// expenseDate must stay @Contextual, or it's sent without superjson's meta and the server rejects it.
@Serializable
public data class ExpenseFormValues(
    public val title: String,
    @Contextual public val expenseDate: Instant,
    public val amount: Int,
    public val category: Int,
    public val paidBy: String,
    public val paidFor: List<PaidFor>,
    public val splitMode: SplitMode,
    public val saveDefaultSplittingOptions: Boolean,
    public val isReimbursement: Boolean,
    public val documents: List<ExpenseDocument>,
    public val notes: String? = null,
    public val recurrenceRule: RecurrenceRule,
    public val originalAmount: Int? = null,
    // JsonNull clears the conversion; a String? would omit the key instead. Use conversionCurrency.
    public val originalCurrency: JsonElement? = null,
    public val conversionRate: LenientDecimal? = null,
) {
    @Serializable
    public data class PaidFor(
        public val participant: String,
        // Share value x100 for EVENLY/BY_SHARES/BY_PERCENTAGE; raw minor units for BY_AMOUNT.
        public val shares: Int,
    )

    public companion object {
        public fun conversionCurrency(code: String?): JsonElement =
            if (code == null) JsonNull else JsonPrimitive(code)
    }
}
