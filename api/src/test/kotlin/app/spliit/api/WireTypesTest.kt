package app.spliit.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class WireTypesTest {
    private val json = Json

    @Test
    fun `every recurrence rule round-trips under the server's name, unknown ones included`() {
        val rules = mapOf(
            RecurrenceRule.None to "NONE",
            RecurrenceRule.Daily to "DAILY",
            RecurrenceRule.Weekly to "WEEKLY",
            RecurrenceRule.Monthly to "MONTHLY",
            RecurrenceRule.Unknown("YEARLY") to "YEARLY",
        )
        for ((rule, wire) in rules) {
            assertEquals("\"$wire\"", json.encodeToString(RecurrenceRule.serializer(), rule))
            assertEquals(rule, json.decodeFromString(RecurrenceRule.serializer(), "\"$wire\""))
        }
        assertFalse(RecurrenceRule.Unknown("YEARLY").isRecognised)
        assertTrue(RecurrenceRule.Monthly.isRecognised)
    }

    @Test
    fun `every activity type round-trips under the server's name, unknown ones included`() {
        val types = mapOf(
            ActivityType.UpdateGroup to "UPDATE_GROUP",
            ActivityType.CreateExpense to "CREATE_EXPENSE",
            ActivityType.UpdateExpense to "UPDATE_EXPENSE",
            ActivityType.DeleteExpense to "DELETE_EXPENSE",
            ActivityType.ScheduleGroupDeletion to "SCHEDULE_GROUP_DELETION",
            ActivityType.RestoreGroup to "RESTORE_GROUP",
            ActivityType.Unknown("ARCHIVE_GROUP") to "ARCHIVE_GROUP",
        )
        for ((type, wire) in types) {
            assertEquals("\"$wire\"", json.encodeToString(ActivityType.serializer(), type))
            assertEquals(type, json.decodeFromString(ActivityType.serializer(), "\"$wire\""))
        }
    }

    @Test
    fun `a decimal reads from a string or a number, keeps its digits, and writes back as a string`() {
        val fromString = json.decodeFromString(LenientDecimal.serializer(), "\"0.92410\"")
        val fromNumber = json.decodeFromString(LenientDecimal.serializer(), "0.9241")

        assertEquals("0.92410", fromString.toString())
        assertEquals(0, fromString.compareTo(fromNumber))
        assertEquals("\"0.92410\"", json.encodeToString(LenientDecimal.serializer(), fromString))
        assertEquals(BigDecimal("163.5"), json.decodeFromString(LenientDecimal.serializer(), "163.5").value)
    }

    @Test
    fun `anything that is not a decimal is a serialization error, not a crash`() {
        assertThrows<SerializationException> { json.decodeFromString(LenientDecimal.serializer(), "\"abc\"") }
        assertThrows<SerializationException> { json.decodeFromString(LenientDecimal.serializer(), "{\"v\":1}") }
    }

    @Test
    fun `a response that is not JSON at all says so`() {
        val error = assertThrows<SerializationException> {
            SuperJson.decodeResponse(ExpenseDocument.serializer(), "<html>Bad gateway</html>")
        }
        assertTrue("not JSON" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a list row rebuilt on the phone reports the document count it was given`() {
        val row = ExpenseListItem(
            id = "e1",
            title = "Dinner",
            amount = 1000,
            createdAt = Instant.parse("2026-10-01T10:00:00Z"),
            expenseDate = Instant.parse("2026-10-01T00:00:00Z"),
            isReimbursement = false,
            splitMode = SplitMode.EVENLY,
            recurrenceRule = RecurrenceRule.None,
            category = null,
            paidBy = Participant("p1", "Ana"),
            paidFor = emptyList(),
            documentCount = 2,
        )

        assertEquals(2, row.documentCount)
    }
}
