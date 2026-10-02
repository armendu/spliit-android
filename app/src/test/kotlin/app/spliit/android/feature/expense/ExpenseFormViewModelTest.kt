package app.spliit.android.feature.expense

import app.spliit.android.feature.groups.FakeRecentGroupsStore
import app.spliit.android.feature.groups.okBody
import app.spliit.core.DefaultSplit
import app.spliit.core.ExpenseFormDraft
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsSnapshot
import app.spliit.core.SplitMode
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale

private const val GROUP_ID = "g1"
private val AMERICAN: Locale = Locale.US

private fun recentGroup(
    instanceBaseUrl: String,
    participantId: String? = null,
    defaultSplit: DefaultSplit? = null,
): RecentGroup = RecentGroup(
    groupId = GROUP_ID,
    instanceBaseUrl = instanceBaseUrl,
    groupName = "Weekend in Lisbon",
    participantId = participantId,
    defaultSplit = defaultSplit,
    lastOpenedAt = Instant.parse("2025-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
)

private val GROUP_JSON = """
    {"id":"$GROUP_ID","name":"Weekend in Lisbon","information":null,"currency":"€",
    "currencyCode":"EUR","createdAt":"2025-01-01T00:00:00.000Z","participants":[
    {"id":"p1","name":"Ana"},{"id":"p2","name":"Bruno"},{"id":"p3","name":"Chloé"}]}
""".trimIndent()

private val CATEGORIES_JSON = """
    {"categories":[{"id":0,"grouping":"Uncategorized","name":"General"},
    {"id":1,"grouping":"Uncategorized","name":"Payment"},
    {"id":8,"grouping":"Food and Drink","name":"Dining Out"}]}
""".trimIndent()

private val EXPENSE_JSON = """
    {"expense":{"id":"e1","groupId":"$GROUP_ID","title":"Dinner","amount":3000,"categoryId":8,
    "category":{"id":8,"grouping":"Food and Drink","name":"Dining Out"},
    "expenseDate":"2025-06-01T00:00:00.000Z","createdAt":"2025-06-01T09:00:00.000Z",
    "paidById":"p2","paidBy":{"id":"p2","name":"Bruno"},
    "paidFor":[{"participantId":"p1","shares":100},{"participantId":"p2","shares":200}],
    "isReimbursement":false,"splitMode":"BY_SHARES","notes":"Split unevenly on purpose.",
    "documents":[],"recurrenceRule":"MONTHLY","originalAmount":null,"originalCurrency":null,
    "conversionRate":null}}
""".trimIndent()

private class Router(private val bodies: Map<String, String>) : Dispatcher() {
    val requests = CopyOnWriteArrayList<Pair<String, String>>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val procedure = bodies.keys.firstOrNull { path.endsWith(it) }
            ?: return MockResponse.Builder().code(404).body("no fixture for $path").build()
        requests += procedure to (request.body?.utf8() ?: "")
        return MockResponse.Builder().code(200).body(okBody(bodies.getValue(procedure))).build()
    }

    fun input(procedure: String): JsonObject =
        Json.parseToJsonElement(requests.last { it.first == procedure }.second)
            .jsonObject.getValue("json").jsonObject

    fun called(procedure: String): Boolean = requests.any { it.first == procedure }
}

class ExpenseFormViewModelTest {
    private val server = MockWebServer()

    @BeforeEach
    fun startServer() = server.start()

    @AfterEach
    fun stopServer() = server.close()

    private fun router(vararg extra: Pair<String, String>): Router {
        val bodies = mutableMapOf(
            "groups.get" to """{"group":$GROUP_JSON}""",
            "categories.list" to CATEGORIES_JSON,
            "groups.expenses.get" to EXPENSE_JSON,
            "groups.expenses.create" to """{"expenseId":"new1"}""",
            "groups.expenses.update" to """{"expenseId":"e1"}""",
            "groups.expenses.delete" to "null",
        )
        bodies += extra
        val router = Router(bodies)
        server.dispatcher = router
        return router
    }

    private fun store(
        participantId: String? = null,
        defaultSplit: DefaultSplit? = null,
    ): FakeRecentGroupsStore = FakeRecentGroupsStore(
        RecentGroupsSnapshot(
            groups = listOf(
                recentGroup(server.url("/").toString(), participantId, defaultSplit),
            ),
        ),
    )

    private fun viewModel(
        mode: ExpenseFormMode,
        store: FakeRecentGroupsStore,
    ): ExpenseFormViewModel = ExpenseFormViewModel(
        groupId = GROUP_ID,
        mode = mode,
        recentGroupsStore = store,
        locale = AMERICAN,
        now = Instant.parse("2025-06-10T12:00:00Z"),
    )

    @Test
    fun `opening an expense for editing round-trips every field`() = runBlocking {
        val router = router()
        val viewModel = viewModel(ExpenseFormMode.Edit("e1"), store())

        viewModel.refresh()

        val state = viewModel.state.value
        assertNull(state.loadError)
        val draft = checkNotNull(state.draft)
        assertEquals("Dinner", draft.title)
        assertEquals("30.00", draft.amountText)
        assertEquals(8, draft.categoryId)
        assertEquals("p2", draft.paidById)
        assertEquals(SplitMode.BY_SHARES, draft.splitMode)
        assertEquals("Split unevenly on purpose.", draft.notes)
        assertEquals("MONTHLY", draft.recurrenceRule)
        assertFalse(draft.isReimbursement)
        assertEquals(Instant.parse("2025-06-01T00:00:00Z"), draft.expenseDate)
        assertEquals(listOf(true, true, false), draft.participants.map { it.isIncluded })
        assertEquals(listOf("1", "2", "1"), draft.participants.map { it.valueText })
        assertTrue(router.requests.any { it.first == "groups.expenses.get" })
        assertEquals("EUR", draft.groupCurrencyCode)
    }

    @Test
    fun `re-opening the sheet starts from the expense again, not from where the last open ended`() =
        runBlocking {
            router()
            val viewModel = viewModel(ExpenseFormMode.Edit("e1"), store())
            viewModel.refresh()
            viewModel.setTitle("Something else entirely")
            assertTrue(viewModel.submit())
            assertNotNull(viewModel.state.value.savedExpenseId)
            assertTrue(viewModel.state.value.isDirty)

            viewModel.rearm()

            assertNull(viewModel.state.value.savedExpenseId)
            assertNull(viewModel.state.value.draft)
            assertFalse(viewModel.state.value.isDirty)

            viewModel.refresh()

            assertEquals("Dinner", checkNotNull(viewModel.state.value.draft).title)
        }

    @Test
    fun `saving an edit sends every field back unchanged, with the participant who made it`() =
        runBlocking {
            val router = router()
            val viewModel = viewModel(ExpenseFormMode.Edit("e1"), store(participantId = "p3"))
            viewModel.refresh()

            assertTrue(viewModel.submit())

            val input = router.input("groups.expenses.update")
            assertEquals("e1", input.getValue("expenseId").jsonPrimitive.content)
            assertEquals("p3", input.getValue("participantId").jsonPrimitive.content)
            val values = input.getValue("expenseFormValues").jsonObject
            assertEquals("Dinner", values.getValue("title").jsonPrimitive.content)
            assertEquals(3000, values.getValue("amount").jsonPrimitive.content.toInt())
            assertEquals("BY_SHARES", values.getValue("splitMode").jsonPrimitive.content)
            assertEquals("MONTHLY", values.getValue("recurrenceRule").jsonPrimitive.content)
            assertEquals(
                listOf(100, 200),
                values.getValue("paidFor").jsonArray.map {
                    it.jsonObject.getValue("shares").jsonPrimitive.content.toInt()
                },
            )
        }

    @Test
    fun `changing the amount is what gets saved`() = runBlocking {
        val router = router()
        val viewModel = viewModel(ExpenseFormMode.Edit("e1"), store(participantId = "p1"))
        viewModel.refresh()

        viewModel.setAmountText("45.50")
        assertTrue(viewModel.state.value.isDirty)
        assertTrue(viewModel.submit())

        val values = router.input("groups.expenses.update").getValue("expenseFormValues").jsonObject
        assertEquals(4550, values.getValue("amount").jsonPrimitive.content.toInt())
    }

    @Test
    fun `creating carries the participant who made it`() = runBlocking {
        val router = router()
        val viewModel = viewModel(ExpenseFormMode.Create, store(participantId = "p2"))
        viewModel.refresh()

        viewModel.setTitle("Taxi")
        viewModel.setAmountText("21.00")
        assertTrue(viewModel.submit())

        val input = router.input("groups.expenses.create")
        assertEquals("p2", input.getValue("participantId").jsonPrimitive.content)
        assertEquals("new1", viewModel.state.value.savedExpenseId)
        val values = input.getValue("expenseFormValues").jsonObject
        assertEquals(2100, values.getValue("amount").jsonPrimitive.content.toInt())
        assertEquals("p2", values.getValue("paidBy").jsonPrimitive.content)
        assertEquals(JsonNull, values.getValue("originalCurrency"))
        assertFalse(values.containsKey("originalAmount"))
        assertFalse(values.containsKey("conversionRate"))
        assertFalse(values.containsKey("notes"))
    }

    @Test
    fun `a write by somebody who has left the group names nobody`() = runBlocking {
        val router = router()
        val viewModel = viewModel(ExpenseFormMode.Create, store(participantId = "gone"))
        viewModel.refresh()

        viewModel.setTitle("Taxi")
        viewModel.setAmountText("21.00")
        assertTrue(viewModel.submit())

        assertFalse(router.input("groups.expenses.create").containsKey("participantId"))
        val values = router.input("groups.expenses.create").getValue("expenseFormValues").jsonObject
        assertEquals("p1", values.getValue("paidBy").jsonPrimitive.content)
    }

    @Test
    fun `a saved default split is applied to a new expense`() = runBlocking {
        router()
        val remembered = DefaultSplit(
            splitMode = SplitMode.BY_PERCENTAGE,
            shares = mapOf("p1" to 7000L, "p2" to 3000L),
        )
        val viewModel = viewModel(ExpenseFormMode.Create, store(defaultSplit = remembered))

        viewModel.refresh()

        val draft = checkNotNull(viewModel.state.value.draft)
        assertEquals(SplitMode.BY_PERCENTAGE, draft.splitMode)
        assertEquals(listOf("70", "30", "1"), draft.participants.map { it.valueText })
        assertEquals(listOf(true, true, false), draft.participants.map { it.isIncluded })
    }

    @Test
    fun `a saved default split naming somebody who has left is dropped whole`() = runBlocking {
        router()
        val stale = DefaultSplit(
            splitMode = SplitMode.BY_PERCENTAGE,
            shares = mapOf("p1" to 7000L, "gone" to 3000L),
        )
        val viewModel = viewModel(ExpenseFormMode.Create, store(defaultSplit = stale))

        viewModel.refresh()

        val draft = checkNotNull(viewModel.state.value.draft)
        assertEquals(SplitMode.EVENLY, draft.splitMode)
        assertEquals(3, draft.includedParticipants.size)
    }

    @Test
    fun `remembering a split stores it on the recent-group row`() = runBlocking {
        router()
        val store = store(participantId = "p1")
        val viewModel = viewModel(ExpenseFormMode.Create, store)
        viewModel.refresh()

        viewModel.setTitle("Rent")
        viewModel.setAmountText("900.00")
        viewModel.setSplitMode(SplitMode.BY_SHARES)
        viewModel.setShareText("p1", "2")
        viewModel.setSaveSplitAsDefault(true)
        assertTrue(viewModel.submit())

        val saved = store.load().groups.single().defaultSplit
        assertEquals(SplitMode.BY_SHARES, saved?.splitMode)
        assertEquals(mapOf("p1" to 200L, "p2" to 100L, "p3" to 100L), saved?.shares)
    }

    @Test
    fun `a settle-up prefills payer, payee, amount and the reimbursement flag`() = runBlocking {
        val router = router()
        val viewModel = viewModel(
            ExpenseFormMode.Settle(fromParticipantId = "p3", toParticipantId = "p1", amountMinorUnits = 1250L),
            store(participantId = "p3"),
        )

        viewModel.refresh()

        val draft = checkNotNull(viewModel.state.value.draft)
        assertEquals("p3", draft.paidById)
        assertEquals(listOf("p1"), draft.includedParticipants.map { it.id })
        assertEquals("12.50", draft.amountText)
        assertTrue(draft.isReimbursement)
        assertFalse(draft.isSplitWorthRemembering)

        assertTrue(viewModel.submit())
        val values = router.input("groups.expenses.create").getValue("expenseFormValues").jsonObject
        assertTrue(values.getValue("isReimbursement").jsonPrimitive.content.toBoolean())
        assertEquals("p3", values.getValue("paidBy").jsonPrimitive.content)
        assertEquals(1250, values.getValue("amount").jsonPrimitive.content.toInt())
        assertEquals(1, values.getValue("category").jsonPrimitive.content.toInt())
    }

    @Test
    fun `switching split mode keeps the participants and recomputes the shares`() = runBlocking {
        router()
        val viewModel = viewModel(ExpenseFormMode.Create, store())
        viewModel.refresh()
        viewModel.setTitle("Dinner")
        viewModel.setAmountText("10.00")
        viewModel.setParticipantIncluded("p3", false)

        viewModel.setSplitMode(SplitMode.BY_AMOUNT)

        val draft = checkNotNull(viewModel.state.value.draft)
        assertEquals(listOf("p1", "p2"), draft.includedParticipants.map { it.id })
        assertEquals(listOf("5.00", "5.00"), draft.includedParticipants.map { it.valueText })
        assertTrue(draft.isValid)

        viewModel.setSplitMode(SplitMode.BY_PERCENTAGE)
        val percentages = checkNotNull(viewModel.state.value.draft)
        assertEquals(listOf("50", "50"), percentages.includedParticipants.map { it.valueText })
        assertEquals(0L, percentages.unallocated)
    }

    @Test
    fun `an uneven amount apportions the odd minor unit to the first in the list`() = runBlocking {
        router()
        val viewModel = viewModel(ExpenseFormMode.Create, store())
        viewModel.refresh()
        viewModel.setAmountText("10.00")

        val amounts = checkNotNull(viewModel.state.value.draft).splitAmounts()
        assertEquals(listOf(334L, 333L, 333L), amounts.map { it.amount })
    }

    @Test
    fun `validation surfaces per field, and only once a save has been attempted`() = runBlocking {
        val router = router()
        val viewModel = viewModel(ExpenseFormMode.Create, store())
        viewModel.refresh()
        viewModel.setTitle("x")

        assertTrue(viewModel.state.value.problems(ExpenseFormDraft.Field.TITLE).isEmpty())

        assertFalse(viewModel.submit())

        assertFalse(router.called("groups.expenses.create"))
        val state = viewModel.state.value
        assertEquals(
            listOf(ExpenseFormDraft.Problem.TitleTooShort),
            state.problems(ExpenseFormDraft.Field.TITLE),
        )
        assertEquals(
            listOf(ExpenseFormDraft.Problem.AmountMissing),
            state.problems(ExpenseFormDraft.Field.AMOUNT),
        )
        assertEquals(
            state.draft?.problems(ExpenseFormDraft.Field.TITLE),
            state.problems(ExpenseFormDraft.Field.TITLE),
        )
    }

    @Test
    fun `a by-amount split that does not add up is refused against the split, not the amount`() =
        runBlocking {
            router()
            val viewModel = viewModel(ExpenseFormMode.Create, store())
            viewModel.refresh()
            viewModel.setTitle("Dinner")
            viewModel.setAmountText("30.00")
            viewModel.setSplitMode(SplitMode.BY_AMOUNT)
            viewModel.setShareText("p1", "5.00")

            assertFalse(viewModel.submit())

            val problems = viewModel.state.value.problems(ExpenseFormDraft.Field.PAID_FOR)
            assertEquals(
                listOf(ExpenseFormDraft.Problem.AmountsDoNotSumToTotal(500L)),
                problems,
            )
            assertTrue(viewModel.state.value.problems(ExpenseFormDraft.Field.AMOUNT).isEmpty())
        }

    @Test
    fun `deleting carries the participant who did it, and can be undone`() = runBlocking {
        val router = router()
        val viewModel = viewModel(ExpenseFormMode.Edit("e1"), store(participantId = "p1"))
        viewModel.refresh()

        assertTrue(viewModel.submitDelete())

        assertEquals("p1", router.input("groups.expenses.delete").getValue("participantId").jsonPrimitive.content)
        assertEquals("e1", router.input("groups.expenses.delete").getValue("expenseId").jsonPrimitive.content)
        val deleted = checkNotNull(viewModel.state.value.deleted)
        assertEquals("Dinner", deleted.title)

        assertTrue(viewModel.submitUndoDelete())
        val recreated = router.input("groups.expenses.create")
        assertEquals("p1", recreated.getValue("participantId").jsonPrimitive.content)
        val values = recreated.getValue("expenseFormValues").jsonObject
        assertEquals("Dinner", values.getValue("title").jsonPrimitive.content)
        assertEquals(3000, values.getValue("amount").jsonPrimitive.content.toInt())
        assertEquals("BY_SHARES", values.getValue("splitMode").jsonPrimitive.content)
        assertEquals("new1", viewModel.state.value.savedExpenseId)
        assertNull(viewModel.state.value.deleted)
    }

    @Test
    fun `letting the undo go by closes the screen with the delete standing`() = runBlocking {
        router()
        val viewModel = viewModel(ExpenseFormMode.Edit("e1"), store(participantId = "p1"))
        viewModel.refresh()
        viewModel.submitDelete()

        viewModel.dismissDeleted()

        assertNull(viewModel.state.value.deleted)
        assertTrue(viewModel.state.value.isFinished)
    }

    @Test
    fun `a configuration change does not throw away what has been typed`() = runBlocking {
        router()
        val viewModel = viewModel(ExpenseFormMode.Create, store())
        viewModel.refresh()
        viewModel.setTitle("Half-written")

        viewModel.load()

        assertEquals("Half-written", viewModel.state.value.draft?.title)
        assertTrue(viewModel.state.value.isDirty)
    }

    @Test
    fun `a cancelled load does not surface as a network error`() = runBlocking {
        val viewModel = viewModel(ExpenseFormMode.Edit("e1"), store())

        val job = launch { viewModel.refresh() }
        yield()
        job.cancel()
        job.join()

        assertNull(viewModel.state.value.loadError)
        assertNull(viewModel.state.value.draft)
    }

    @Test
    fun `a category list this instance will not answer costs the chips, not the screen`() =
        runBlocking {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path.endsWith("categories.list") ->
                            MockResponse.Builder().code(500).body("").build()
                        path.endsWith("groups.get") ->
                            MockResponse.Builder().code(200).body(okBody("""{"group":$GROUP_JSON}""")).build()
                        else -> MockResponse.Builder().code(404).body("").build()
                    }
                }
            }
            val viewModel = viewModel(ExpenseFormMode.Create, store())

            viewModel.refresh()

            assertNull(viewModel.state.value.loadError)
            assertNotNull(viewModel.state.value.draft)
            assertTrue(viewModel.state.value.categories.isEmpty())
        }
}
