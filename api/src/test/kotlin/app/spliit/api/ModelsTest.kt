package app.spliit.api

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

// The envelopes the procedures answer with. They live here, not in the module: Part 4 owns the
// endpoint surface, and Part 3 owns only what is inside these wrappers.
@Serializable
private data class GroupResponse(val group: Group)

@Serializable
private data class GroupDetailsResponse(val group: Group, val participantsWithExpenses: List<String>)

@Serializable
private data class GroupsListResponse(val groups: List<GroupSummary>)

@Serializable
private data class CategoriesResponse(val categories: List<ExpenseCategory>)

@Serializable
private data class ExpensesListResponse(
    val expenses: List<ExpenseListItem>,
    val hasMore: Boolean,
    val nextCursor: Int? = null,
)

@Serializable
private data class ExpenseResponse(val expense: ExpenseDetails)

@Serializable
private data class BalancesResponse(
    val balances: Map<String, Balance>,
    val reimbursements: List<Reimbursement>,
)

@Serializable
private data class ActivitiesResponse(val activities: List<Activity>, val hasMore: Boolean)

/**
 * The totals screen's payload, in the shape Part 4 will declare it.
 *
 * [totalParticipantShare] is a `Double` here deliberately, and the test below is why: it is the
 * one amount in the API that is not an integer.
 */
@Serializable
private data class GroupStatsResponse(
    val totalGroupSpendings: Int,
    val totalParticipantSpendings: Double? = null,
    val totalParticipantShare: Double? = null,
)

/** As an instance older than the *Shares* change sends it: a rounded sum of thirds. */
@Serializable
private data class StrictStatsResponse(val totalParticipantShare: Int)

private fun <T> decode(serializer: kotlinx.serialization.DeserializationStrategy<T>, fixture: String): T =
    SuperJson.decodeResponse(serializer, Fixture.text(fixture))

/** For the rules that no recorded response happens to exercise, e.g. a future activity type. */
private fun <T> decodeBody(serializer: kotlinx.serialization.DeserializationStrategy<T>, json: String): T =
    SuperJson.decodeResponse(serializer, """{"result":{"data":{"json":$json}}}""")

class ModelsTest {

    // ---- one per recorded fixture ------------------------------------------------------

    @Test
    fun `a group carries its participants, its symbol and its ISO code`() {
        val group = decode(GroupResponse.serializer(), "groups.get").group

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
        // There is no `participantCount` on the wire: Prisma answers with a `_count` object, and
        // that is the only place the number is. A model reading a plain field decodes nothing and
        // every group in the list claims to have no members.
        val summaries = decode(GroupsListResponse.serializer(), "groups.list").groups

        val lisbon = summaries.single { it.name == "Weekend in Lisbon" }
        assertEquals(3, lisbon.participantCount)
        assertEquals("€", lisbon.currency)
        assertEquals(2, summaries.single { it.name == "Book club" }.participantCount)
    }

    /**
     * `groups.list` builds `createdAt` with `.toISOString()` and sends it with no entry in
     * `meta.values` at all, unlike every other endpoint. The models are statically typed, so it
     * decodes anyway, which is what justifies ignoring the metadata rather than consulting it.
     */
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
        // Same trap as the group summary's, on the field that decides whether a row shows a
        // paperclip.
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
        // The detail payload identifies each payee by ID alone, where the list nests a whole
        // participant. The form screen needs the IDs, so this is the useful half.
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
        // Only `total` means anything here: the server derives `paid` and `paidFor` from the
        // suggested payments rather than from the expenses, so one of the two is always zero and
        // the other is abs(total). The assertion pins that shape so nobody reads them as spending.
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
        val activities = decode(ActivitiesResponse.serializer(), "groups.activities.list").activities

        assertTrue(activities.isNotEmpty())
        assertTrue(activities.any { it.activityType == ActivityType.UpdateGroup })
        assertTrue(activities.any { it.activityType == ActivityType.CreateExpense })
        assertTrue(activities.any { it.activityType == ActivityType.UpdateExpense })

        // The title is the expense's name *as it was* when the row was written; the server keeps
        // it in a column called `data`.
        val deleted = activities.single { it.activityType == ActivityType.DeleteExpense }
        assertEquals("Pastéis de Belém", deleted.title)
        // Deleted, so there is nothing left to open, the whole point of the flag.
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

        // Without a participant the two per-person totals come back as superjson `undefined`,
        // which reaches the payload as a JSON null.
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

        // This instance has the overview and not the old name, so its answer to the old one is a
        // recorded example of the 404 Part 4's fallback has to recognise.
        assertTrue(SuperJson.decodeError(Fixture.text("groups.stats.get"))!!.isUnknownProcedure)
    }

    // ---- the rules that bite ------------------------------------------------------------

    /**
     * An instance older than the web app's *Shares* change sums floating-point thirds and rounds
     * to two decimals, sending `1416.67`. An `Int` field throws on it and takes the whole totals
     * screen down, against real servers, for a subset of users.
     */
    @Test
    fun `totalParticipantShare decodes a non-integer`() {
        val body = """{"totalGroupSpendings":4250,"totalParticipantShare":1416.67}"""

        val decoded = decodeBody(GroupStatsResponse.serializer(), body)

        assertEquals(1416.67, decoded.totalParticipantShare)
        // And what the mistake costs, so the reason this field is a Double is written down where
        // somebody tidying types would read it.
        assertThrows<SerializationException> {
            decodeBody(StrictStatsResponse.serializer(), body)
        }
    }

    @Test
    fun `an unrecognised activity type decodes as unknown rather than throwing`() {
        // Instances are self-hosted and may be ahead of us. A kind we have no sentence for should
        // cost the log one row, not the whole tab.
        val body = """
            {"activities":[{"id":"a1","groupId":"g1","time":"2026-09-01T10:00:00.000Z",
            "activityType":"ARCHIVE_GROUP","participantId":null,"expenseId":null,
            "data":null,"expense":null}],"hasMore":false}
        """.trimIndent()

        val activity = decodeBody(ActivitiesResponse.serializer(), body).activities.single()

        assertEquals(ActivityType.Unknown("ARCHIVE_GROUP"), activity.activityType)
        assertFalse(activity.activityType.isRecognised)
    }

    /**
     * The same decision as [ActivityType], and for the same reason turned up one notch: the rule
     * is display-only in cycle 1, so an unreadable one should cost its own row a line of detail.
     * As an enum it costs the whole screen, the other two expenses here decode perfectly and are
     * lost anyway, which is what makes the trade so lopsided.
     */
    @Test
    fun `an unrecognised recurrence rule decodes as unknown and leaves the other expenses intact`() {
        val listed = decodeBody(ExpensesListResponse.serializer(), expensesWithRules("NONE", "YEARLY", "MONTHLY"))

        assertEquals(3, listed.expenses.size)
        val unknown = listed.expenses.single { it.title == "Expense 1" }
        assertEquals(RecurrenceRule.Unknown("YEARLY"), unknown.recurrenceRule)
        assertFalse(unknown.recurrenceRule!!.isRecognised)

        // The expenses either side of it are untouched, which is the whole point.
        assertEquals(RecurrenceRule.None, listed.expenses.single { it.title == "Expense 0" }.recurrenceRule)
        assertEquals(RecurrenceRule.Monthly, listed.expenses.single { it.title == "Expense 2" }.recurrenceRule)
        assertTrue(listed.expenses.all { it.amount == 1000 })
    }

    @Test
    fun `an unknown recurrence rule is sent back under the server's own name`() {
        // An expense edited on a screen that could not name its rule must be saved with the rule
        // it already had, rather than silently reset to NONE by the round trip.
        val rule: RecurrenceRule = RecurrenceRule.Unknown("YEARLY")

        val encoded = SuperJson.encodeEnvelope(RecurrenceRule.serializer(), rule)

        assertTrue(encoded.contains("\"YEARLY\""), encoded)
    }

    /**
     * The opposite decision from [ActivityType] and [RecurrenceRule], on purpose: a split mode
     * this client cannot read is money it would divide wrongly, so it fails loudly rather than
     * guessing. The contrast with the test above is the point, these are three considered
     * answers to the same question, not three inconsistent ones.
     */
    @Test
    fun `an unrecognised split mode throws rather than guessing`() {
        val body = """
            {"expenses":[{"id":"e1","title":"x","amount":100,
            "createdAt":"2026-09-01T10:00:00.000Z","expenseDate":"2026-09-01T00:00:00.000Z",
            "isReimbursement":false,"splitMode":"BY_PHASE_OF_THE_MOON","recurrenceRule":"NONE",
            "category":null,"paidBy":{"id":"p1","name":"Ana"},"paidFor":[],
            "_count":{"documents":0}}],"hasMore":false}
        """.trimIndent()

        assertThrows<SerializationException> { decodeBody(ExpensesListResponse.serializer(), body) }
    }

    @Test
    fun `a conversion rate decodes from a Prisma decimal sent as a string`() {
        // Recorded from the server: a `Prisma.Decimal` crosses superjson as a string, annotated
        // `[["custom","decimal.js"]]` rather than as a number.
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

    // ---- money semantics -----------------------------------------------------------------

    @Test
    fun `an amount is carried as minor units, whatever the currency counts in`() {
        // 1234 is 12.34 in a two-decimal currency and ¥1,234 in yen. Nothing in the model may
        // assume hundredths, formatting is Part 5's job and needs the raw count intact.
        val body = """{"expense":${convertedExpenseJson(amount = 1234)}}"""

        assertEquals(1234, decodeBody(ExpenseResponse.serializer(), body).expense.amount)
    }

    @Test
    fun `shares are a share value times 100 except under BY_AMOUNT, where they are minor units`() {
        val expenses = decode(ExpensesListResponse.serializer(), "groups.expenses.list").expenses

        // Chloé took the double room and carries two of the four shares: 2 × 100.
        val apartment = expenses.single { it.title == "Apartment" }
        assertEquals(SplitMode.BY_SHARES, apartment.splitMode)
        assertEquals(200, apartment.paidFor.single { it.participant.name == "Chloé" }.shares)
        assertEquals(100, apartment.paidFor.single { it.participant.name == "Ana" }.shares)

        // Same field, different unit: under BY_AMOUNT the shares are minor-unit amounts and sum
        // to the expense total.
        val tram = expenses.single { it.title == "Tram tickets" }
        assertEquals(SplitMode.BY_AMOUNT, tram.splitMode)
        assertEquals(tram.amount, tram.paidFor.sumOf { it.shares })

        // And percentages are also ×100: 60% is 6000.
        val internet = decodeBody(
            ExpensesListResponse.serializer(),
            """
            {"expenses":[{"id":"e1","title":"Internet","amount":6000,
            "createdAt":"2026-09-01T10:00:00.000Z","expenseDate":"2026-09-01T00:00:00.000Z",
            "isReimbursement":false,"splitMode":"BY_PERCENTAGE","recurrenceRule":"NONE",
            "category":null,"paidBy":{"id":"p1","name":"Dana"},
            "paidFor":[{"participant":{"id":"p1","name":"Dana"},"shares":6000},
            {"participant":{"id":"p2","name":"Eli"},"shares":4000}],
            "_count":{"documents":0}}],"hasMore":false}
            """.trimIndent(),
        ).expenses.single()
        assertEquals(10_000, internet.paidFor.sumOf { it.shares })
    }

    @Test
    fun `a converted expense carries two amounts on two different scales`() {
        // `originalAmount` is in `originalCurrency`'s minor units and `amount` is in the group's.
        // Formatting either with the other's currency is a bug that looks plausible.
        val expense = decode(ExpenseResponse.serializer(), "groups.expenses.get.converted").expense

        assertEquals(20000, expense.originalAmount)
        assertEquals("USD", expense.originalCurrency)
        assertEquals(18482, expense.amount)
        assertEquals(BigDecimal("0.9241"), expense.conversionRate?.value)
    }

    /** Three valid rows differing only in their recurrence rule. */
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
        return """{"expenses":[${expenses.joinToString(",")}],"hasMore":false}"""
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
