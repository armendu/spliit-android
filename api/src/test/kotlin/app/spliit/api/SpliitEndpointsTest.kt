package app.spliit.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val EXPENSE_DATE: Instant = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

private fun expenseForm(): ExpenseFormValues = ExpenseFormValues(
    title = "Airport taxi",
    expenseDate = EXPENSE_DATE,
    amount = 4250,
    category = 0,
    paidBy = "ana",
    paidFor = listOf(ExpenseFormValues.PaidFor(participant = "ana", shares = 100)),
    splitMode = SplitMode.EVENLY,
    saveDefaultSplittingOptions = false,
    isReimbursement = false,
    documents = emptyList(),
    recurrenceRule = RecurrenceRule.None,
)

private fun groupForm(): GroupFormValues = GroupFormValues(
    name = "Weekend in Lisbon",
    information = "",
    currency = "€",
    currencyCode = "EUR",
    participants = listOf(GroupFormValues.Participant(id = "ana", name = "Ana")),
)

/** A tRPC success envelope carrying [json] as its payload, unparsed so tests can inline it. */
private fun okBody(json: String): String = """{"result":{"data":{"json":$json}}}"""

class SpliitEndpointsTest {

    private val server = MockWebServer()
    private lateinit var client: TrpcClient

    @BeforeEach
    fun startServer() {
        server.start()
        client = TrpcClient(server.url("/").toString())
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    private fun enqueue(json: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(okBody(json)).build())
    }

    private fun enqueueRaw(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    /** The `json` object a query sent as its `input`, or a mutation sent as its body. */
    private fun sentInput(recorded: RecordedRequest): JsonObject {
        val envelope = if (recorded.method == "GET") {
            recorded.url.queryParameter("input") ?: error("no input query parameter on ${recorded.target}")
        } else {
            recorded.body!!.utf8()
        }
        return Json.parseToJsonElement(envelope).jsonObject.getValue("json").jsonObject
    }

    private fun path(recorded: RecordedRequest): String = recorded.url.encodedPath.substringAfterLast("/api/trpc/")

    // ============================================================================================
    // One test per procedure: the path it hits and the shape of what it actually sends.
    // Reading procedures also decode the recorded fixture, proving the response wrapper agrees
    // with the server, not just with our own idea of the shape.
    // ============================================================================================

    // ---- Groups --------------------------------------------------------------------------

    @Test
    fun `groups list decodes the recorded fixture and sends groupIds`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.list")).build())

        val response = client.call(SpliitEndpoints.groupsList(listOf("g1", "g2")))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("groups.list", path(recorded))
        // Distinct from a no-input procedure: an explicit `input` parameter is always present,
        // even though every other query here also sends one, the contrast is with categories.list.
        assertEquals(listOf("g1", "g2"), sentInput(recorded).getValue("groupIds").jsonArray.map { it.jsonPrimitive.content })

        assertEquals(3, response.groups.size)
        assertTrue(response.groups.any { it.name == "Weekend in Lisbon" })
    }

    @Test
    fun `groups get decodes a null group when no group has that id`() = runBlocking {
        // No recorded fixture covers a deleted group, the seeded instance has nothing to delete
        // to produce one, so this is the one deliberately hand-written response in this suite.
        enqueue("""{"group":null}""")

        val response = client.call(SpliitEndpoints.groupsGet("does-not-exist"))

        val recorded = server.takeRequest()
        assertEquals("groups.get", path(recorded))
        assertEquals("does-not-exist", sentInput(recorded).getValue("groupId").jsonPrimitive.content)
        assertNull(response.group)
    }

    @Test
    fun `groups get decodes a real group from the recorded fixture`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.get")).build())

        val response = client.call(SpliitEndpoints.groupsGet("g1"))

        val recorded = server.takeRequest()
        assertEquals("groups.get", path(recorded))
        assertEquals("g1", sentInput(recorded).getValue("groupId").jsonPrimitive.content)
        assertEquals("Weekend in Lisbon", response.group?.name)
    }

    @Test
    fun `groups getDetails sends the group id and decodes participants with expenses`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.getDetails")).build())

        val response = client.call(SpliitEndpoints.groupsGetDetails("g1"))

        val recorded = server.takeRequest()
        assertEquals("groups.getDetails", path(recorded))
        assertEquals("g1", sentInput(recorded).getValue("groupId").jsonPrimitive.content)
        assertEquals(
            response.group.participants.map { it.id }.toSet(),
            response.participantsWithExpenses.toSet(),
        )
    }

    @Test
    fun `groups create sends the form values and decodes the new group id`() = runBlocking {
        enqueue("""{"groupId":"new-group"}""")

        val response = client.call(SpliitEndpoints.groupsCreate(groupForm()))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("groups.create", path(recorded))
        val sent = sentInput(recorded).getValue("groupFormValues").jsonObject
        assertEquals("Weekend in Lisbon", sent.getValue("name").jsonPrimitive.content)
        assertEquals("new-group", response.groupId)
    }

    @Test
    fun `groups update sends the participant id when one is given`() = runBlocking {
        enqueue("null")

        client.call(SpliitEndpoints.groupsUpdate("g1", groupForm(), participantId = "ana"))

        val recorded = server.takeRequest()
        assertEquals("groups.update", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("g1", sent.getValue("groupId").jsonPrimitive.content)
        assertEquals("ana", sent.getValue("participantId").jsonPrimitive.content)
    }

    /**
     * The trap this whole procedure exists to warn about: omitting `participantId` still
     * succeeds and still writes, so nothing short of reading the actual request body would
     * catch a caller, or a refactor, that silently stopped naming the actor. `encodeDefaults
     * = false` means a Kotlin `null` here is an *absent* key, never a JSON `null`.
     */
    @Test
    fun `groups update omits participant id entirely rather than sending null`() = runBlocking {
        enqueue("null")

        client.call(SpliitEndpoints.groupsUpdate("g1", groupForm()))

        val recorded = server.takeRequest()
        val sent = sentInput(recorded)
        assertFalse(sent.containsKey("participantId"), "expected no participantId key, got $sent")
    }

    // ---- Expenses ------------------------------------------------------------------------

    @Test
    fun `expenses list sends its paging and filter and decodes the recorded fixture`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.expenses.list")).build())

        val response = client.call(SpliitEndpoints.expensesList("g1", cursor = 0, limit = 20, filter = "taxi"))

        val recorded = server.takeRequest()
        assertEquals("groups.expenses.list", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("g1", sent.getValue("groupId").jsonPrimitive.content)
        assertEquals(0, sent.getValue("cursor").jsonPrimitive.content.toInt())
        assertEquals(20, sent.getValue("limit").jsonPrimitive.content.toInt())
        assertEquals("taxi", sent.getValue("filter").jsonPrimitive.content)
        assertEquals(4, response.expenses.size)
        assertFalse(response.hasMore)
    }

    @Test
    fun `expenses get sends the group and expense id and decodes the recorded fixture`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.expenses.get")).build())

        val response = client.call(SpliitEndpoints.expensesGet("g1", "e1"))

        val recorded = server.takeRequest()
        assertEquals("groups.expenses.get", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("g1", sent.getValue("groupId").jsonPrimitive.content)
        assertEquals("e1", sent.getValue("expenseId").jsonPrimitive.content)
        assertEquals("Airport taxi", response.expense.title)
    }

    @Test
    fun `expenses create sends the form values and the participant id`() = runBlocking {
        enqueue("""{"expenseId":"new-expense"}""")

        val response = client.call(SpliitEndpoints.expensesCreate("g1", expenseForm(), participantId = "ana"))

        val recorded = server.takeRequest()
        assertEquals("groups.expenses.create", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("g1", sent.getValue("groupId").jsonPrimitive.content)
        assertEquals("ana", sent.getValue("participantId").jsonPrimitive.content)
        assertTrue(sent.containsKey("expenseFormValues"))
        assertEquals("new-expense", response.expenseId)
    }

    @Test
    fun `expenses update sends the participant id when one is given`() = runBlocking {
        enqueue("""{"expenseId":"e1"}""")

        val response =
            client.call(SpliitEndpoints.expensesUpdate("g1", "e1", expenseForm(), participantId = "ana"))

        val recorded = server.takeRequest()
        assertEquals("groups.expenses.update", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("g1", sent.getValue("groupId").jsonPrimitive.content)
        assertEquals("e1", sent.getValue("expenseId").jsonPrimitive.content)
        assertEquals("ana", sent.getValue("participantId").jsonPrimitive.content)
        assertTrue(sent.containsKey("expenseFormValues"))
        assertEquals("e1", response.expenseId)
    }

    @Test
    fun `expenses update omits participant id when none is given`() = runBlocking {
        enqueue("""{"expenseId":"e1"}""")

        client.call(SpliitEndpoints.expensesUpdate("g1", "e1", expenseForm()))

        val recorded = server.takeRequest()
        assertEquals("groups.expenses.update", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("e1", sent.getValue("expenseId").jsonPrimitive.content)
        assertFalse(sent.containsKey("participantId"))
    }

    @Test
    fun `expenses delete sends the group and expense id and returns nothing`() = runBlocking {
        enqueue("null")

        client.call(SpliitEndpoints.expensesDelete("g1", "e1", participantId = "ana"))

        val recorded = server.takeRequest()
        assertEquals("groups.expenses.delete", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("g1", sent.getValue("groupId").jsonPrimitive.content)
        assertEquals("e1", sent.getValue("expenseId").jsonPrimitive.content)
        assertEquals("ana", sent.getValue("participantId").jsonPrimitive.content)
    }

    // ---- Balances --------------------------------------------------------------------------

    @Test
    fun `balances list sends the group id and decodes the recorded fixture`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.balances.list")).build())

        val response = client.call(SpliitEndpoints.balancesList("g1"))

        val recorded = server.takeRequest()
        assertEquals("groups.balances.list", path(recorded))
        assertEquals("g1", sentInput(recorded).getValue("groupId").jsonPrimitive.content)
        assertEquals(3, response.balances.size)
        assertEquals(2, response.reimbursements.size)
    }

    // ---- Activity --------------------------------------------------------------------------

    @Test
    fun `activities list sends its paging and decodes the recorded fixture`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.activities.list")).build())

        val response = client.call(SpliitEndpoints.activitiesList("g1", cursor = 0, limit = 20))

        val recorded = server.takeRequest()
        assertEquals("groups.activities.list", path(recorded))
        val sent = sentInput(recorded)
        assertEquals("g1", sent.getValue("groupId").jsonPrimitive.content)
        assertEquals(0, sent.getValue("cursor").jsonPrimitive.content.toInt())
        assertEquals(20, sent.getValue("limit").jsonPrimitive.content.toInt())
        assertTrue(response.activities.isNotEmpty())
    }

    // ---- Categories ------------------------------------------------------------------------

    @Test
    fun `categories list sends no input parameter at all`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("categories.list")).build())

        val response = client.call(SpliitEndpoints.categoriesList())

        val recorded = server.takeRequest()
        assertEquals("categories.list", path(recorded))
        // Not merely an empty `input=`, the parameter itself must be absent, which is what tells
        // NoInput apart from an encoded null further up the stack.
        assertNull(recorded.url.queryParameter("input"))
        assertNull(recorded.url.query)
        assertTrue(response.categories.isNotEmpty())
    }

    // ============================================================================================
    // groups.stats, the fallback, and the field the rest of this file is not about.
    // ============================================================================================

    @Test
    fun `stats overview and stats get both send the group id and an optional participant id`() {
        val overview = client.buildRequest(SpliitEndpoints.statsOverview("g1", participantId = "ana"))
        assertTrue(overview.url.encodedPath.endsWith("groups.stats.overview"))
        assertEquals(
            "ana",
            Json.parseToJsonElement(overview.url.queryParameter("input")!!)
                .jsonObject.getValue("json").jsonObject.getValue("participantId").jsonPrimitive.content,
        )

        val get = client.buildRequest(SpliitEndpoints.statsGet("g1"))
        assertTrue(get.url.encodedPath.endsWith("groups.stats.get"))
        assertFalse(
            Json.parseToJsonElement(get.url.queryParameter("input")!!)
                .jsonObject.getValue("json").jsonObject.containsKey("participantId"),
        )
    }

    @Test
    fun `groupStats falls back from overview to get on an unknown procedure and returns the value`() = runBlocking {
        // The overview name 404s the way an instance that has never heard of it would, recorded
        // ground truth for what that answer looks like, and the second call succeeds, recorded
        // from an instance that does understand it (the shape is identical either name).
        server.enqueue(
            MockResponse.Builder().code(404).body(Fixture.text("error.unknown-procedure")).build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.stats.overview")).build())

        val result = client.groupStats("g1")

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("groups.stats.overview", path(first))
        assertEquals("groups.stats.get", path(second))
        assertEquals(63680, result.totalGroupSpendings)
    }

    @Test
    fun `groupStats rethrows when the instance answers neither name`() {
        server.enqueue(
            MockResponse.Builder().code(404).body(Fixture.text("error.unknown-procedure")).build(),
        )
        // Recorded ground truth: our own e2e image serves only `overview`, so its real answer to
        // the old name is exactly the second 404 that must reach the caller here.
        server.enqueue(MockResponse.Builder().code(404).body(Fixture.text("groups.stats.get")).build())

        val error = assertThrows<TrpcServerError> { runBlocking { client.groupStats("g1") } }

        assertTrue(error.isUnknownProcedure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `groupStats does not fall back on an ordinary NOT_FOUND`() {
        // "Group not found." is NOT_FOUND too, but it is not a missing *route*, falling back here
        // would silently ask a second, unrelated procedure instead of surfacing the real problem.
        server.enqueue(MockResponse.Builder().code(404).body(Fixture.text("error.not-found")).build())

        val error = assertThrows<TrpcServerError> { runBlocking { client.groupStats("g1") } }

        assertFalse(error.isUnknownProcedure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `groupStats does not fall back on a 500`() {
        server.enqueue(MockResponse.Builder().code(500).body("").build())

        assertThrows<TrpcClientError.UnexpectedResponse> { runBlocking { client.groupStats("g1") } }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the anonymous totals fixture decodes nulls for the per-participant figures`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(Fixture.text("groups.stats.overview.anonymous")).build(),
        )

        val result = client.groupStats("g1")

        assertEquals(63680, result.totalGroupSpendings)
        assertNull(result.totalParticipantSpendings)
        assertNull(result.totalParticipantShare)
    }

    /**
     * An instance older than the web app's *Shares* change sums floating-point thirds and rounds
     * to two decimals, sending `1416.67`. No recorded fixture carries this, every server we can
     * point at has the change, so this is deliberately hand-written to pin the one field that
     * must never become an `Int`. Typing it that way decodes every other test in this file green
     * and throws only here, against a real server, for a subset of self-hosted users.
     */
    @Test
    fun `totalParticipantShare decodes a non-integer without throwing`() {
        val body = okBody("""{"totalGroupSpendings":4250,"totalParticipantShare":1416.67}""")

        val decoded = SuperJson.decodeResponse(SpliitEndpoints.GroupStatsResponse.serializer(), body)

        assertEquals(1416.67, decoded.totalParticipantShare)
    }

    @Test
    fun `the overview fixture decodes the summary and the category breakdown`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Fixture.text("groups.stats.overview")).build())

        val result = client.groupStats("g1")

        val summary = requireNotNull(result.summary)
        assertEquals(4, summary.expenseCount)
        assertEquals(63680, summary.totalSpending)
        assertEquals(15920, summary.averageExpense)
        assertEquals("Apartment", summary.largestExpense?.title)
        assertEquals(48000, summary.largestExpense?.amount)
        // Date-only strings, not superjson `Date`s, the envelope annotates neither.
        assertEquals("2025-08-11", summary.firstDate)
        assertEquals("2026-09-14", summary.lastDate)

        val categories = requireNotNull(result.categories)
        assertEquals(1, categories.size)
        assertEquals("Uncategorized", categories[0].grouping)
        assertEquals("General", categories[0].name)
        assertEquals(63680, categories[0].total)
    }

    /**
     * The shape the **removed** `groups.stats.get` answers with: the three top-level figures and
     * nothing else. Everything the overview added has to decode as absent rather than throw, or
     * the totals tab breaks on exactly the self-hosted instances the fallback exists for.
     */
    @Test
    fun `a three-figure payload leaves every field the overview added null`() {
        val body = okBody(
            """{"totalGroupSpendings":63680,"totalParticipantSpendings":6050,"totalParticipantShare":17627}""",
        )

        val decoded = SuperJson.decodeResponse(SpliitEndpoints.GroupStatsResponse.serializer(), body)

        assertEquals(63680, decoded.totalGroupSpendings)
        assertNull(decoded.summary)
        assertNull(decoded.categories)
    }
}
