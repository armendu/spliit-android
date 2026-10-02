package app.spliit.api

import app.spliit.api.SpliitEndpoints.CreateExpenseInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val EXPENSE_DATE: Instant =
    OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

private fun expenseForm(
    originalCurrency: kotlinx.serialization.json.JsonElement? =
        ExpenseFormValues.conversionCurrency(null),
    originalAmount: Int? = null,
    conversionRate: LenientDecimal? = null,
    notes: String? = null,
): ExpenseFormValues = ExpenseFormValues(
    title = "Airport taxi",
    expenseDate = EXPENSE_DATE,
    amount = 4250,
    category = 0,
    paidBy = "ana",
    paidFor = listOf(
        ExpenseFormValues.PaidFor(participant = "ana", shares = 100),
        ExpenseFormValues.PaidFor(participant = "bruno", shares = 100),
    ),
    splitMode = SplitMode.EVENLY,
    saveDefaultSplittingOptions = false,
    isReimbursement = false,
    documents = emptyList(),
    notes = notes,
    recurrenceRule = RecurrenceRule.None,
    originalAmount = originalAmount,
    originalCurrency = originalCurrency,
    conversionRate = conversionRate,
)

private fun groupForm(
    currencyCode: String = "EUR",
    information: String = "",
    participants: List<GroupFormValues.Participant> = listOf(
        GroupFormValues.Participant(id = "ana", name = "Ana"),
        GroupFormValues.Participant(name = "Bruno"),
    ),
): GroupFormValues = GroupFormValues(
    name = "Weekend in Lisbon",
    information = information,
    currency = "€",
    currencyCode = currencyCode,
    participants = participants,
)

class FormValuesTest {
    private fun envelope(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun payload(body: String): JsonObject = envelope(body).getValue("json").jsonObject

    private fun annotations(body: String): JsonObject =
        envelope(body).getValue("meta").jsonObject.getValue("values").jsonObject

    @Test
    fun `expenseDate is annotated as a Date in the envelope`() {
        val body = SuperJson.encodeEnvelope(ExpenseFormValues.serializer(), expenseForm())

        assertEquals(setOf("expenseDate"), annotations(body).keys)
        assertEquals("2026-09-01T00:00:00.000Z", payload(body).getValue("expenseDate").jsonPrimitive.content)
    }

    @Test
    fun `a nested expense form still annotates its date at the right key path`() {
        val body = SuperJson.encodeEnvelope(
            CreateExpenseInput.serializer(),
            CreateExpenseInput(groupId = "g1", participantId = "ana", expenseFormValues = expenseForm()),
        )

        assertEquals(setOf("expenseFormValues.expenseDate"), annotations(body).keys)
    }

    @Test
    fun `originalCurrency is sent as an explicit null when conversion is dropped`() {
        val body = SuperJson.encodeEnvelope(ExpenseFormValues.serializer(), expenseForm())

        val json = payload(body)
        assertTrue(json.containsKey("originalCurrency"))
        assertEquals(JsonNull, json.getValue("originalCurrency"))
    }

    @Test
    fun `originalCurrency is sent as a code when the expense was converted`() {
        val body = SuperJson.encodeEnvelope(
            ExpenseFormValues.serializer(),
            expenseForm(
                originalCurrency = ExpenseFormValues.conversionCurrency("USD"),
                originalAmount = 20000,
                conversionRate = LenientDecimal(BigDecimal("0.9241")),
            ),
        )

        val json = payload(body)
        assertEquals("USD", json.getValue("originalCurrency").jsonPrimitive.content)
        assertEquals(20000, json.getValue("originalAmount").jsonPrimitive.content.toInt())
        assertEquals("0.9241", json.getValue("conversionRate").jsonPrimitive.content)
        assertTrue(json.getValue("conversionRate").jsonPrimitive.isString)
    }

    @Test
    fun `originalAmount and conversionRate are omitted rather than nulled`() {
        val body = SuperJson.encodeEnvelope(ExpenseFormValues.serializer(), expenseForm())

        val json = payload(body)
        assertFalse(json.containsKey("originalAmount"))
        assertFalse(json.containsKey("conversionRate"))
    }

    @Test
    fun `notes are omitted when there are none`() {
        val body = SuperJson.encodeEnvelope(ExpenseFormValues.serializer(), expenseForm())

        assertFalse(payload(body).containsKey("notes"))
    }

    // With encodeDefaults = false, a Kotlin default on a required field would drop it from the request.
    @Test
    fun `every required field is sent even when it holds the value a default would have`() {
        val body = SuperJson.encodeEnvelope(ExpenseFormValues.serializer(), expenseForm())

        val json = payload(body)
        assertEquals(
            setOf(
                "title", "expenseDate", "amount", "category", "paidBy", "paidFor", "splitMode",
                "saveDefaultSplittingOptions", "isReimbursement", "documents", "recurrenceRule",
                "originalCurrency",
            ),
            json.keys,
        )
        assertEquals("EVENLY", json.getValue("splitMode").jsonPrimitive.content)
        assertEquals("NONE", json.getValue("recurrenceRule").jsonPrimitive.content)
        assertEquals(false, json.getValue("isReimbursement").jsonPrimitive.content.toBoolean())
        assertEquals(JsonArray(emptyList()), json.getValue("documents"))
    }

    @Test
    fun `a paid-for entry names the participant by id and carries its shares verbatim`() {
        val body = SuperJson.encodeEnvelope(ExpenseFormValues.serializer(), expenseForm())

        val paidFor = payload(body).getValue("paidFor").jsonArray
        assertEquals("ana", paidFor[0].jsonObject.getValue("participant").jsonPrimitive.content)
        assertEquals(100, paidFor[0].jsonObject.getValue("shares").jsonPrimitive.content.toInt())
    }

    @Test
    fun `a cleared currency code is sent as an empty string, not omitted`() {
        val body = SuperJson.encodeEnvelope(GroupFormValues.serializer(), groupForm(currencyCode = ""))

        val json = payload(body)
        assertTrue(json.containsKey("currencyCode"))
        assertEquals("", json.getValue("currencyCode").jsonPrimitive.content)
    }

    @Test
    fun `cleared information is sent as an empty string`() {
        val body = SuperJson.encodeEnvelope(GroupFormValues.serializer(), groupForm(information = ""))

        assertEquals("", payload(body).getValue("information").jsonPrimitive.content)
    }

    @Test
    fun `a new participant is sent without an id, an existing one with it`() {
        val body = SuperJson.encodeEnvelope(GroupFormValues.serializer(), groupForm())

        val participants = payload(body).getValue("participants").jsonArray
        assertEquals("ana", participants[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertFalse(participants[1].jsonObject.containsKey("id"))
        assertEquals("Bruno", participants[1].jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `a group form carries no date and so no meta block at all`() {
        val body = SuperJson.encodeEnvelope(GroupFormValues.serializer(), groupForm())

        assertFalse(envelope(body).containsKey("meta"))
    }
}
