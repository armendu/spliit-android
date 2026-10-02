package app.spliit.api

import app.spliit.api.SpliitEndpoints.ActivitiesListResponse
import app.spliit.api.SpliitEndpoints.BalancesResponse
import app.spliit.api.SpliitEndpoints.CategoriesResponse
import app.spliit.api.SpliitEndpoints.ExpenseResponse
import app.spliit.api.SpliitEndpoints.ExpensesListResponse
import app.spliit.api.SpliitEndpoints.GroupDetailsResponse
import app.spliit.api.SpliitEndpoints.GroupResponse
import app.spliit.api.SpliitEndpoints.GroupStatsResponse
import app.spliit.api.SpliitEndpoints.GroupsListResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

@Serializable
private data class StrictStatsResponse(val totalParticipantShare: Int)

private fun <T> decode(serializer: kotlinx.serialization.DeserializationStrategy<T>, fixture: String): T =
    SuperJson.decodeResponse(serializer, Fixture.text(fixture))

private fun <T> decodeBody(serializer: kotlinx.serialization.DeserializationStrategy<T>, json: String): T =
    SuperJson.decodeResponse(serializer, """{"result":{"data":{"json":$json}}}""")

class ModelsTest {
    @Test
    fun `a group carries its participants, its symbol and its ISO code`() {
        val group = decode(GroupResponse.serializer(), "groups.get").group!!

        assertEquals("Weekend in Lisbon", group.name)
        assertEquals("€", group.currency)
        assertEquals("EUR", group.currencyCode)
        assertEquals(listOf("Ana", "Bruno", "Chloé"), group.participants.map { it.name })
        assertTrue(group.information!!.startsWith("Shared costs"))
        assertTrue(group.createdAt.isAfter(Instant.EPOCH))
    }

    @Test
    fun `group details carry the participants who appear in an expense`() {
        val details = decode(GroupDetailsResponse.serializer(), "groups.getDetails")

        assertEquals("Weekend in Lisbon", details.group.name)
        assertEquals(
            details.group.participants.map { it.id }.toSet(),
            details.participantsWithExpenses.toSet(),
        )
    }

    @Test
    fun `a group summary decodes its participant count from the _count aggregate`() {
        val summaries = decode(GroupsListResponse.serializer(), "groups.list").groups

        val lisbon = summaries.single { it.name == "Weekend in Lisbon" }
        assertEquals(3, lisbon.participantCount)
        assertEquals("€", lisbon.currency)
        assertEquals(2, summaries.single { it.name == "Book club" }.participantCount)
    }

    @Test
    fun `a date the server did not annotate still decodes`() {
        val body = Fixture.text("groups.list")
        assertFalse(body.contains("\"meta\""), "The fixture stopped being the unannotated case.")

        val summaries = decode(GroupsListResponse.serializer(), "groups.list").groups

        assertTrue(summaries.all { it.createdAt.isAfter(Instant.EPOCH) })
    }

    @Test
    fun `the category list decodes its groupings`() {
        val categories = decode(CategoriesResponse.serializer(), "categories.list").categories

        val general = categories.single { it.id == 0 }
        assertEquals("General", general.name)
        assertEquals("Uncategorized", general.grouping)
        assertTrue(categories.size > 1)
    }

    @Test
    fun `an expense list decodes amounts, payers and categories`() {
        val listed = decode(ExpensesListResponse.serializer(), "groups.expenses.list")

        assertEquals(4, listed.expenses.size)
        assertFalse(listed.hasMore)

        val taxi = listed.expenses.single { it.title == "Airport taxi" }
        assertEquals(4250, taxi.amount)
        assertEquals(SplitMode.EVENLY, taxi.splitMode)
        assertEquals(RecurrenceRule.None, taxi.recurrenceRule)
        assertEquals("Ana", taxi.paidBy.name)
        assertEquals(3, taxi.paidFor.size)
        assertEquals("General", taxi.category?.name)
        assertFalse(taxi.isReimbursement)
        assertTrue(taxi.expenseDate.isAfter(Instant.EPOCH))
    }

    @Test
    fun `an expense row decodes its document count from the _count aggregate`() {
        val listed = decode(ExpensesListResponse.serializer(), "groups.expenses.list")

        assertEquals(0, listed.expenses.single { it.title == "Airport taxi" }.documentCount)
    }

    @Test
    fun `expense details decode the editable fields and name the payer by id`() {
        val expense = decode(ExpenseResponse.serializer(), "groups.expenses.get").expense

        assertEquals("Airport taxi", expense.title)
        assertEquals(4250, expense.amount)
        assertEquals(0, expense.categoryId)
        assertEquals("General", expense.category?.name)
        assertEquals("Ana", expense.paidBy.name)
        assertEquals(expense.paidBy.id, expense.paidById)
        assertEquals(3, expense.paidFor.size)
        assertTrue(expense.paidFor.any { it.participantId == expense.paidById })
        assertNull(expense.notes)
        assertEquals(emptyList<ExpenseDocument>(), expense.documents)
        assertEquals(SplitMode.EVENLY, expense.splitMode)
        assertNull(expense.originalCurrency)
        assertNull(expense.originalAmount)
        assertNull(expense.conversionRate)
    }

    @Test
    fun `balances and reimbursements decode`() {
        val balances = decode(BalancesResponse.serializer(), "groups.balances.list")

        assertEquals(3, balances.balances.size)
        balances.balances.values.forEach { balance ->
            assertEquals(balance.paid - balance.paidFor, balance.total)
            assertTrue(balance.paid == 0 || balance.paidFor == 0)
        }
        assertEquals(0, balances.balances.values.sumOf { it.total })

        val owed = balances.reimbursements.single { it.amount == 11577 }
        assertTrue(owed.from.isNotEmpty())
        assertTrue(owed.to.isNotEmpty())
    }

    @Test
    fun `an activity log decodes its kinds, its titles and whether the expense survives`() {
        val activities = decode(ActivitiesListResponse.serializer(), "groups.activities.list").activities

        assertTrue(activities.isNotEmpty())
        assertTrue(activities.any { it.activityType == ActivityType.UpdateGroup })
        assertTrue(activities.any { it.activityType == ActivityType.CreateExpense })
        assertTrue(activities.any { it.activityType == ActivityType.UpdateExpense })

        val deleted = activities.single { it.activityType == ActivityType.DeleteExpense }
        assertEquals("Pastéis de Belém", deleted.title)
        assertFalse(deleted.expenseStillExists)
        assertNotNull(deleted.expenseId)

        val updated = activities.first { it.activityType == ActivityType.UpdateExpense }
        assertTrue(updated.expenseStillExists)
        assertNotNull(updated.participantId)
        assertTrue(updated.time.isAfter(Instant.EPOCH))
    }

    @Test
    fun `the totals payload decodes, named and anonymous`() {
        val named = decode(GroupStatsResponse.serializer(), "groups.stats.overview")
        assertEquals(63680, named.totalGroupSpendings)
        assertNotNull(named.totalParticipantShare)

        val anonymous = decode(GroupStatsResponse.serializer(), "groups.stats.overview.anonymous")
        assertEquals(63680, anonymous.totalGroupSpendings)
        assertNull(anonymous.totalParticipantShare)
        assertNull(anonymous.totalParticipantSpendings)
    }

    @Test
    fun `an error body decodes, and a missing route is told apart from a missing group`() {
        val missingGroup = SuperJson.decodeError(Fixture.text("error.not-found"))!!
        assertEquals("NOT_FOUND", missingGroup.code)
        assertFalse(missingGroup.isUnknownProcedure)

        val missingRoute = SuperJson.decodeError(Fixture.text("error.unknown-procedure"))!!
        assertTrue(missingRoute.isUnknownProcedure)

        assertTrue(SuperJson.decodeError(Fixture.text("groups.stats.get"))!!.isUnknownProcedure)
    }

    @Test
    fun `totalParticipantShare decodes a non-integer`() {
        val body = """{"totalGroupSpendings":4250,"totalParticipantShare":1416.67}"""

        val decoded = decodeBody(GroupStatsResponse.serializer(), body)

        assertEquals(1416.67, decoded.totalParticipantShare)
        assertThrows<SerializationException> {
            decodeBody(StrictStatsResponse.serializer(), body)
        }
    }

    @Test
    fun `an unrecognised activity type decodes as unknown rather than throwing`() {
        val body = """
            {"activities":[{"id":"a1","groupId":"g1","time":"2026-09-01T10:00:00.000Z",
            "activityType":"ARCHIVE_GROUP","participantId":null,"expenseId":null,
            "data":null,"expense":null}],"hasMore":false,"nextCursor":0}
        """.trimIndent()

        val activity = decodeBody(ActivitiesListResponse.serializer(), body).activities.single()

        assertEquals(ActivityType.Unknown("ARCHIVE_GROUP"), activity.activityType)
        assertFalse(activity.activityType.isRecognised)
    }

    @Test
    fun `an unrecognised recurrence rule decodes as unknown and leaves the other expenses intact`() {
        val listed = decodeBody(ExpensesListResponse.serializer(), expensesWithRules("NONE", "YEARLY", "MONTHLY"))

        assertEquals(3, listed.expenses.size)
        val unknown = listed.expenses.single { it.title == "Expense 1" }
        assertEquals(RecurrenceRule.Unknown("YEARLY"), unknown.recurrenceRule)
        assertFalse(unknown.recurrenceRule!!.isRecognised)

        assertEquals(RecurrenceRule.None, listed.expenses.single { it.title == "Expense 0" }.recurrenceRule)
        assertEquals(RecurrenceRule.Monthly, listed.expenses.single { it.title == "Expense 2" }.recurrenceRule)
        assertTrue(listed.expenses.all { it.amount == 1000 })
    }

    @Test
    fun `an unknown recurrence rule is sent back under the server's own name`() {
        val rule: RecurrenceRule = RecurrenceRule.Unknown("YEARLY")

        val encoded = SuperJson.encodeEnvelope(RecurrenceRule.serializer(), rule)

        assertTrue(encoded.contains("\"YEARLY\""), encoded)
    }

    @Test
    fun `an unrecognised split mode throws rather than guessing`() {
        val body = """
            {"expenses":[{"id":"e1","title":"x","amount":100,
            "createdAt":"2026-09-01T10:00:00.000Z","expenseDate":"2026-09-01T00:00:00.000Z",
            "isReimbursement":false,"splitMode":"BY_PHASE_OF_THE_MOON","recurrenceRule":"NONE",
            "category":null,"paidBy":{"id":"p1","name":"Ana"},"paidFor":[],
            "_count":{"documents":0}}],"hasMore":false,"nextCursor":0}
        """.trimIndent()

        assertThrows<SerializationException> { decodeBody(ExpensesListResponse.serializer(), body) }
    }

    @Test
    fun `a conversion rate decodes from a Prisma decimal sent as a string`() {
        assertTrue(Fixture.text("groups.expenses.get.converted").contains("\"conversionRate\":\"0.9241\""))

        val expense = decode(ExpenseResponse.serializer(), "groups.expenses.get.converted").expense

        assertEquals(BigDecimal("0.9241"), expense.conversionRate?.value)
    }

    @Test
    fun `a conversion rate also decodes from an instance that sends it as a number`() {
        val body = """{"expense":${convertedExpenseJson(rate = "0.9241")}}"""

        val expense = decodeBody(ExpenseResponse.serializer(), body).expense

        assertEquals(BigDecimal("0.9241"), expense.conversionRate?.value)
    }

    @Test
    fun `an amount is carried as minor units, whatever the currency counts in`() {
        val body = """{"expense":${convertedExpenseJson(amount = 1234)}}"""

        assertEquals(1234, decodeBody(ExpenseResponse.serializer(), body).expense.amount)
    }

    @Test
    fun `shares are a share value times 100 except under BY_AMOUNT, where they are minor units`() {
        val expenses = decode(ExpensesListResponse.serializer(), "groups.expenses.list").expenses

        val apartment = expenses.single { it.title == "Apartment" }
        assertEquals(SplitMode.BY_SHARES, apartment.splitMode)
        assertEquals(200, apartment.paidFor.single { it.participant.name == "Chloé" }.shares)
        assertEquals(100, apartment.paidFor.single { it.participant.name == "Ana" }.shares)

        val tram = expenses.single { it.title == "Tram tickets" }
        assertEquals(SplitMode.BY_AMOUNT, tram.splitMode)
        assertEquals(tram.amount, tram.paidFor.sumOf { it.shares })

        val internet = decodeBody(
            ExpensesListResponse.serializer(),
            """
            {"expenses":[{"id":"e1","title":"Internet","amount":6000,
            "createdAt":"2026-09-01T10:00:00.000Z","expenseDate":"2026-09-01T00:00:00.000Z",
            "isReimbursement":false,"splitMode":"BY_PERCENTAGE","recurrenceRule":"NONE",
            "category":null,"paidBy":{"id":"p1","name":"Dana"},
            "paidFor":[{"participant":{"id":"p1","name":"Dana"},"shares":6000},
            {"participant":{"id":"p2","name":"Eli"},"shares":4000}],
            "_count":{"documents":0}}],"hasMore":false,"nextCursor":0}
            """.trimIndent(),
        ).expenses.single()
        assertEquals(10_000, internet.paidFor.sumOf { it.shares })
    }

    @Test
    fun `a converted expense carries two amounts on two different scales`() {
        val expense = decode(ExpenseResponse.serializer(), "groups.expenses.get.converted").expense

        assertEquals(20000, expense.originalAmount)
        assertEquals("USD", expense.originalCurrency)
        assertEquals(18482, expense.amount)
        assertEquals(BigDecimal("0.9241"), expense.conversionRate?.value)
    }

    private fun expensesWithRules(vararg rules: String): String {
        val expenses = rules.mapIndexed { index, rule ->
            """
            {"id":"e$index","title":"Expense $index","amount":1000,
            "createdAt":"2026-09-01T10:00:00.000Z","expenseDate":"2026-09-01T00:00:00.000Z",
            "isReimbursement":false,"splitMode":"EVENLY","recurrenceRule":"$rule",
            "category":null,"paidBy":{"id":"p1","name":"Ana"},
            "paidFor":[{"participant":{"id":"p1","name":"Ana"},"shares":100}],
            "_count":{"documents":0}}
            """.trimIndent()
        }
        return """{"expenses":[${expenses.joinToString(",")}],"hasMore":false,"nextCursor":0}"""
    }

    private fun convertedExpenseJson(amount: Int = 18482, rate: String = "0.9241"): String = """
        {"id":"e1","groupId":"g1","title":"Hotel","amount":$amount,"categoryId":0,
        "category":null,"expenseDate":"2026-09-01T00:00:00.000Z",
        "createdAt":"2026-09-01T10:00:00.000Z","paidById":"p1",
        "paidBy":{"id":"p1","name":"Ana"},
        "paidFor":[{"participantId":"p1","shares":100}],
        "isReimbursement":false,"splitMode":"EVENLY","notes":null,"documents":[],
        "recurrenceRule":"NONE","originalAmount":20000,"originalCurrency":"USD",
        "conversionRate":$rate}
    """.trimIndent()
}
