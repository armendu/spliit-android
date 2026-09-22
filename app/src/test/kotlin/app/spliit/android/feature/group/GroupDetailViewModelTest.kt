package app.spliit.android.feature.group

import app.spliit.android.feature.groups.FakeRecentGroupsStore
import app.spliit.android.feature.groups.okBody
import app.spliit.core.DateBucket
import app.spliit.core.LoadState
import app.spliit.core.RecentGroup
import app.spliit.core.RecentGroupsSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import app.spliit.core.RefreshLimiter
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private fun recentGroup(id: String, instanceBaseUrl: String, participantId: String? = null): RecentGroup =
    RecentGroup(
        groupId = id,
        instanceBaseUrl = instanceBaseUrl,
        groupName = "local name for $id",
        participantId = participantId,
        lastOpenedAt = Instant.parse("2025-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
    )

private fun participantJson(id: String, name: String) = """{"id":"$id","name":"$name"}"""

private fun groupJson(id: String, name: String, participantsJson: String) = """
    {"id":"$id","name":"$name","information":null,"currency":"€","currencyCode":"EUR",
    "createdAt":"2025-01-01T00:00:00.000Z","participants":[$participantsJson]}
""".trimIndent()

private fun expenseJson(id: String, title: String, amount: Int, expenseDate: String) = """
    {"id":"$id","title":"$title","amount":$amount,"createdAt":"$expenseDate","expenseDate":"$expenseDate",
    "isReimbursement":false,"splitMode":"EVENLY","recurrenceRule":"NONE","category":null,
    "paidBy":{"id":"p1","name":"Ana"},"paidFor":[],"_count":{"documents":0}}
""".trimIndent()

/** One expense as `groups.expenses.get` answers it, payees by ID, where a row nests a whole
 *  participant. The gap the in-place update has to bridge from the group it already holds. */
private fun expenseDetailJson(id: String, title: String, amount: Int, expenseDate: String) = """
    {"expense":{"id":"$id","groupId":"g1","title":"$title","amount":$amount,"categoryId":0,
    "category":null,"expenseDate":"$expenseDate","createdAt":"$expenseDate",
    "paidById":"p1","paidBy":{"id":"p1","name":"Ana"},
    "paidFor":[{"participantId":"pGone","shares":100},{"participantId":"p2","shares":100},{"participantId":"p1","shares":100}],
    "isReimbursement":false,"splitMode":"EVENLY","notes":null,"documents":[],
    "recurrenceRule":"NONE","originalAmount":null,"originalCurrency":null,"conversionRate":null}}
""".trimIndent()

private fun expensesListJson(expensesJson: String, hasMore: Boolean, nextCursor: Int) =
    """{"expenses":[$expensesJson],"hasMore":$hasMore,"nextCursor":$nextCursor}"""

private fun balancesListJson(balancesJson: String, reimbursementsJson: String = "[]") =
    """{"balances":{$balancesJson},"reimbursements":$reimbursementsJson}"""

/**
 * Routes each request to the right canned body by tRPC procedure path, since [GroupDetailViewModel
 * .refresh] fires the group, the first expense page and the balances all at once, a plain FIFO
 * queue would hand a concurrent request whichever response happens to be next, not the one that
 * matches it.
 */
private class ProcedureDispatcher(private val bodies: Map<String, String>) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val procedure = bodies.keys.firstOrNull { path.endsWith(it) }
            ?: return MockResponse.Builder().code(404).body("no fixture for $path").build()
        return MockResponse.Builder().code(200).body(okBody(bodies.getValue(procedure))).build()
    }
}

class GroupDetailViewModelTest {

    private val server = MockWebServer()

    @BeforeEach
    fun startServer() = server.start()

    @AfterEach
    fun stopServer() = server.close()

    private fun route(bodies: Map<String, String>) {
        server.dispatcher = ProcedureDispatcher(bodies)
    }

    @Test
    fun `pages the expense list by the offset cursor`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson(
                    expenseJson("e1", "Taxi", 1000, "2025-06-01T00:00:00.000Z"),
                    hasMore = true,
                    nextCursor = 20,
                ),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()

        val firstPage = (viewModel.state.value.expenses as LoadState.Loaded).value
        assertEquals(listOf("e1"), firstPage.expenses.map { it.id })
        assertTrue(firstPage.hasMore)
        assertEquals(20, firstPage.nextCursor)

        // The next page is asked for at the offset cursor the first page handed back, 20, not
        // a key derived from the last expense's id.
        var sentCursor: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                sentCursor = request.url.queryParameter("input")
                return MockResponse.Builder().code(200).body(
                    okBody(
                        expensesListJson(
                            expenseJson("e2", "Dinner", 2000, "2025-05-30T00:00:00.000Z"),
                            hasMore = false,
                            nextCursor = 40,
                        ),
                    ),
                ).build()
            }
        }

        viewModel.loadNextExpensesPage()

        val secondPage = (viewModel.state.value.expenses as LoadState.Loaded).value
        assertEquals(listOf("e1", "e2"), secondPage.expenses.map { it.id })
        assertFalse(secondPage.hasMore)
        assertTrue(sentCursor?.contains("\"cursor\":20") == true, "expected cursor 20 in $sentCursor")
    }

    @Test
    fun `saving an edit reads one expense and the balances, never the expense list again`() =
        runBlocking {
            // The whole reason editing is a sheet: the group screen stays composed, so nothing
            // re-runs on its own and only what changed is read. A `groups.expenses.list` here
            // would be the skeleton-and-lost-scroll-position bug coming back.
            val instance = server.url("/").toString()
            val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
            route(
                mapOf(
                    "groups.get" to """{"group":${groupJson(
                        "g1",
                        "Lisbon",
                        participantJson("p1", "Ana") + "," + participantJson("p2", "Bruno"),
                    )}}""",
                    "groups.expenses.list" to expensesListJson(
                        expenseJson("e1", "Taxi", 1000, "2025-06-02T00:00:00.000Z") + "," +
                            expenseJson("e2", "Dinner", 2000, "2025-06-01T00:00:00.000Z"),
                        hasMore = false,
                        nextCursor = 20,
                    ),
                    "groups.balances.list" to balancesListJson(""""p1":{"paid":0,"paidFor":0,"total":500}"""),
                ),
            )
            val viewModel = GroupDetailViewModel("g1", store)
            viewModel.refresh()

            val asked = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    asked += path.substringAfterLast("/")
                    val body = when {
                        path.endsWith("groups.expenses.get") ->
                            expenseDetailJson("e2", "Dinner, amended", 4200, "2025-06-03T00:00:00.000Z")
                        path.endsWith("groups.balances.list") ->
                            balancesListJson(""""p1":{"paid":0,"paidFor":0,"total":1700}""")
                        else -> return MockResponse.Builder().code(404).body("unexpected $path").build()
                    }
                    return MockResponse.Builder().code(200).body(okBody(body)).build()
                }
            }

            viewModel.applySavedExpense("e2")

            assertFalse(
                asked.any { it == "groups.expenses.list" },
                "the whole list was read again: $asked",
            )
            assertTrue(asked.contains("groups.balances.list"), "the balances were not recomputed: $asked")
            val page = (viewModel.state.value.expenses as LoadState.Loaded).value
            // The edited row carries the new values, and its new date moved it to the top.
            assertEquals(listOf("e2", "e1"), page.expenses.map { it.id })
            val edited = page.expenses.first()
            assertEquals("Dinner, amended", edited.title)
            assertEquals(4200, edited.amount)
            // Payee names come from the group already loaded, in the order the detail payload
            // gives them, not the group's, which would make this row read differently from the
            // same row after a reload. One the group no longer has is dropped rather than drawn
            // as a blank.
            assertEquals(listOf("Bruno", "Ana"), edited.paidFor.map { it.participant.name })
            assertEquals(1700L, (viewModel.state.value.balances as LoadState.Loaded).value.balances["p1"])
        }

    @Test
    fun `placing an expense keeps every other row where it was`() {
        val rows = listOf(
            decodeExpense("e1", "2025-06-03T00:00:00.000Z"),
            decodeExpense("e2", "2025-06-02T00:00:00.000Z"),
            decodeExpense("e3", "2025-06-01T00:00:00.000Z"),
        )
        // Several expenses share a day routinely, and their order within it is the server's -
        // so an edit that left the date alone leaves the row exactly where it was.
        val sameDay = listOf(
            decodeExpense("a", "2025-06-02T00:00:00.000Z"),
            decodeExpense("b", "2025-06-02T00:00:00.000Z"),
            decodeExpense("c", "2025-06-01T00:00:00.000Z"),
        )
        assertEquals(
            listOf("a", "b", "c"),
            placed(sameDay, decodeExpense("a", "2025-06-02T00:00:00.000Z")).map { it.id },
        )
        // An edit that moved the date moves exactly one row.
        assertEquals(
            listOf("e1", "e3", "e2"),
            placed(rows, decodeExpense("e2", "2025-05-31T00:00:00.000Z")).map { it.id },
        )
        // An undone delete comes back under a new ID and lands by its date, not at the end.
        assertEquals(
            listOf("e1", "e4", "e2", "e3"),
            placed(rows, decodeExpense("e4", "2025-06-02T12:00:00.000Z")).map { it.id },
        )
    }

    @Test
    fun `expenses group under the right date buckets`() {
        val clock = Clock.fixed(Instant.parse("2025-06-10T12:00:00Z"), ZoneOffset.UTC)
        val expenses = listOf(
            decodeExpense("today", "2025-06-10T08:00:00.000Z"),
            decodeExpense("lastMonth", "2025-05-02T08:00:00.000Z"),
            decodeExpense("older", "2023-01-01T08:00:00.000Z"),
        )

        val sections = bucketExpenses(expenses, clock)

        assertEquals(
            listOf(DateBucket.TODAY, DateBucket.LAST_MONTH, DateBucket.OLDER),
            sections.map { it.bucket },
        )
        assertEquals(listOf("today"), sections[0].expenses.map { it.id })
        assertEquals(listOf("lastMonth"), sections[1].expenses.map { it.id })
        assertEquals(listOf("older"), sections[2].expenses.map { it.id })
    }

    @Test
    fun `the balances tab reads total and never paid or paidFor`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                // paid and paidFor are deliberately implausible next to total, so a bug reading
                // either would be caught immediately rather than by coincidence.
                "groups.balances.list" to balancesListJson(""""p1":{"paid":999999,"paidFor":888888,"total":-1234}"""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)

        viewModel.refresh()

        val balances = (viewModel.state.value.balances as LoadState.Loaded).value
        assertEquals(mapOf("p1" to -1234L), balances.balances)
    }

    @Test
    fun `reimbursements render as suggested payments`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(
                    """"p1":{"paid":0,"paidFor":500,"total":-500}""",
                    reimbursementsJson = """[{"from":"p1","to":"p2","amount":500}]""",
                ),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)

        viewModel.refresh()

        val balances = (viewModel.state.value.balances as LoadState.Loaded).value
        assertEquals(1, balances.reimbursements.size)
        assertEquals("p1", balances.reimbursements[0].from)
        assertEquals("p2", balances.reimbursements[0].to)
        assertEquals(500, balances.reimbursements[0].amount)
    }

    @Test
    fun `the user's own balance leads once they have identified themselves`() {
        val identified = GroupDetailUiState(
            balances = LoadState.Loaded(BalancesInfo(balances = mapOf("p1" to -500L), reimbursements = emptyList())),
            activeParticipantId = "p1",
        )
        assertEquals(-500L, identified.yourBalanceMinorUnits())

        val unidentified = identified.copy(activeParticipantId = null)
        assertNull(unidentified.yourBalanceMinorUnits())

        // No activity for this participant is a real zero, not a missing answer.
        val noActivity = identified.copy(activeParticipantId = "p2")
        assertEquals(0L, noActivity.yourBalanceMinorUnits())
    }

    @Test
    fun `a failed load surfaces retry, not an empty list`() = runBlocking {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.close()

        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", deadUrl))))
        val viewModel = GroupDetailViewModel("g1", store)

        viewModel.refresh()

        assertTrue(viewModel.state.value.group is LoadState.Failed)
        assertTrue(viewModel.state.value.expenses is LoadState.Failed)
        assertTrue(viewModel.state.value.balances is LoadState.Failed)
    }

    @Test
    fun `actorId resolves, and is null when the participant has left`() = runBlocking {
        val instance = server.url("/").toString()

        // p1 is remembered as this phone's participant, and the group still has them.
        val store = FakeRecentGroupsStore(
            RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance, participantId = "p1"))),
        )
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()
        assertEquals("p1", viewModel.state.value.activeParticipantId)

        // Now the remembered participant has left the group, the fresh response no longer
        // names them, so the write must never claim to be someone who is gone.
        val store2 = FakeRecentGroupsStore(
            RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance, participantId = "p1"))),
        )
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p2", "Bruno"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel2 = GroupDetailViewModel("g1", store2)
        viewModel2.refresh()
        assertNull(viewModel2.state.value.activeParticipantId)
    }

    @Test
    fun `a cancelled load does not surface as a network error`() = runBlocking {
        // No response is ever enqueued, the request hangs until this test cancels it.
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        val viewModel = GroupDetailViewModel("g1", store)

        val job = launch { viewModel.refresh() }
        yield()
        job.cancel()
        job.join()

        assertEquals(LoadState.Loading, viewModel.state.value.group)
        assertEquals(LoadState.Loading, viewModel.state.value.expenses)
        assertEquals(LoadState.Loading, viewModel.state.value.balances)
    }

    // ---- what a loaded group costs to re-enter -------------------------------------------------

    @Test
    fun `loadIfNeeded asks for nothing once the group is loaded`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()
        val afterFirstLoad = server.requestCount
        assertEquals(3, afterFirstLoad, "the first load is the group, the first expense page and the balances")

        // The screen's own LaunchedEffect(Unit) re-runs on every fresh composition, coming back
        // from the expense form, from the group editor, from anywhere. This is the guard that
        // makes that free.
        viewModel.loadIfNeeded()
        yield()

        assertEquals(afterFirstLoad, server.requestCount)
    }

    @Test
    fun `the totals are not asked for until the tab is opened`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                asked += path.substringAfterLast('/')
                val body = when {
                    path.endsWith("groups.get") ->
                        """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}"""
                    path.endsWith("groups.expenses.list") -> expensesListJson("", hasMore = false, nextCursor = 0)
                    path.endsWith("groups.balances.list") -> balancesListJson("")
                    path.endsWith("groups.stats.overview") ->
                        """{"totalGroupSpendings":4200,"totalParticipantSpendings":null,
                           "totalParticipantShare":null,
                           "categories":[{"categoryId":0,"grouping":"Uncategorized","name":"General","total":4200}]}"""
                            .trimIndent()
                    else -> return MockResponse.Builder().code(404).body("no fixture for $path").build()
                }
                return MockResponse.Builder().code(200).body(okBody(body)).build()
            }
        }
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()

        assertFalse(asked.any { it.startsWith("groups.stats") }, "nothing should have asked for totals yet")

        viewModel.loadStats(app.spliit.api.TrpcClient(instance), participantId = null)

        assertTrue(asked.contains("groups.stats.overview"))
        val stats = (viewModel.state.value.stats as LoadState.Loaded).value
        assertEquals(4200L, stats.totalGroupSpendings)
        assertEquals(listOf(4200), stats.categories.map { it.total })
    }

    @Test
    fun `an instance answering neither stats name says so rather than offering a retry`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()

        // Both names 404 with the shape tRPC uses for a route it has never heard of.
        val unknownProcedure = """{"error":{"json":{"message":"No procedure found on path \"x\"",
            "code":-32004,"data":{"code":"NOT_FOUND","httpStatus":404,"path":"x"}}}}""".trimIndent()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(404).body(unknownProcedure).build()
        }

        viewModel.loadStats(app.spliit.api.TrpcClient(instance), participantId = null)

        assertTrue(viewModel.state.value.statsUnavailable)
        assertEquals(LoadState.Failed(null), viewModel.state.value.stats)
    }

    @Test
    fun `an expense change refreshes the totals only once the tab has been opened`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        val statsBody = """{"totalGroupSpendings":4200,"totalParticipantSpendings":null,"totalParticipantShare":null}"""
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                asked += path.substringAfterLast('/')
                val body = when {
                    path.endsWith("groups.get") ->
                        """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}"""
                    path.endsWith("groups.expenses.list") -> expensesListJson("", hasMore = false, nextCursor = 0)
                    path.endsWith("groups.balances.list") -> balancesListJson("")
                    path.endsWith("groups.stats.overview") -> statsBody
                    path.endsWith("groups.activities.list") -> """{"activities":[],"hasMore":false,"nextCursor":0}"""
                    else -> return MockResponse.Builder().code(404).body("no fixture for $path").build()
                }
                return MockResponse.Builder().code(200).body(okBody(body)).build()
            }
        }
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()

        // Nobody has opened Totals or the activity log, so an expense change reads neither, and
        // the group and the categories are never read by this path at all.
        asked.clear()
        viewModel.applyExpenseChange()
        // A set, not a list: the two go out concurrently and finish in whatever order the
        // server answers them.
        assertEquals(setOf("groups.expenses.list", "groups.balances.list"), asked.toSet())
        assertFalse(asked.contains("groups.get"))
        assertFalse(asked.contains("categories.list"))

        // Open Totals once, and from then on it is kept current.
        viewModel.loadStats(app.spliit.api.TrpcClient(instance), participantId = null)
        asked.clear()
        viewModel.applyExpenseChange()
        assertTrue(asked.contains("groups.stats.overview"))
        assertFalse(asked.contains("groups.get"))
    }

    @Test
    fun `a group edit reads the group back and never the expense list`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()

        val group = (viewModel.state.value.group as LoadState.Loaded).value
        // Everything the information tab needs comes off the same read.
        assertEquals(instance, group.instanceBaseUrl)
        assertEquals("EUR", group.currencyCode)
        assertNull(group.information)
    }

    // ---- search --------------------------------------------------------------------------------

    @Test
    fun `search sends the typed text as the server-side filter`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()

        var sentInput: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                sentInput = request.url.queryParameter("input")
                return MockResponse.Builder().code(200).body(
                    okBody(
                        expensesListJson(
                            expenseJson("e1", "Taxi", 1000, "2025-06-01T00:00:00.000Z"),
                            hasMore = false,
                            nextCursor = 1,
                        ),
                    ),
                ).build()
            }
        }

        viewModel.search("  taxi  ")
        // The field keeps exactly what was typed; only the query sent is trimmed.
        assertEquals("  taxi  ", viewModel.state.value.search.query)
        viewModel.runSearch("taxi")

        assertTrue(sentInput?.contains("\"filter\":\"taxi\"") == true, "expected filter in $sentInput")
        val results = viewModel.state.value.search.results as LoadState.Loaded
        assertEquals(listOf("e1"), results.value.map { it.id })
    }

    @Test
    fun `a cancelled search does not report a failure`() = runBlocking {
        // The keystroke after this one is already searching. CLAUDE.md: TrpcClient propagates a
        // real CancellationException rather than dressing it up as a network failure, and a
        // cancelled search has nothing to say, reporting it put "Couldn't search" on screen
        // between characters for anyone typing slower than the debounce.
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(okBody(expensesListJson("", hasMore = false, nextCursor = 0)))
                    // Long enough that the cancellation below lands inside the request.
                    .bodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
        }

        viewModel.search("ta")
        val job = launch { viewModel.runSearch("ta") }
        yield()
        job.cancel()
        job.join()

        assertFalse(
            viewModel.state.value.search.results is LoadState.Failed,
            "a cancelled search must not leave an error on screen",
        )
    }

    @Test
    fun `emptying the field drops the results rather than asking for the whole group`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        route(
            mapOf(
                "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
                "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
                "groups.balances.list" to balancesListJson(""),
            ),
        )
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()
        val before = server.requestCount

        viewModel.search("")

        assertNull(viewModel.state.value.search.results)
        assertEquals(before, server.requestCount)
    }

    // ---- the activity log ----------------------------------------------------------------------

    @Test
    fun `the activity log is not read until it is opened`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        val asked = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                asked += path.substringAfterLast('/')
                val body = when {
                    path.endsWith("groups.get") ->
                        """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}"""
                    path.endsWith("groups.expenses.list") -> expensesListJson("", hasMore = false, nextCursor = 0)
                    path.endsWith("groups.balances.list") -> balancesListJson("")
                    path.endsWith("groups.activities.list") -> activitiesJson
                    else -> return MockResponse.Builder().code(404).body("no fixture for $path").build()
                }
                return MockResponse.Builder().code(200).body(okBody(body)).build()
            }
        }
        val viewModel = GroupDetailViewModel("g1", store)
        viewModel.refresh()
        assertFalse(asked.contains("groups.activities.list"))

        viewModel.loadFirstActivitiesPage(app.spliit.api.TrpcClient(instance))

        assertTrue(asked.contains("groups.activities.list"))
        val page = (viewModel.state.value.activities as LoadState.Loaded).value
        assertEquals(listOf("a1", "a2"), page.activities.map { it.id })
        // The title is the activity's own `data` column, the expense's title *as it was*.
        assertEquals("Taxi", page.activities[0].title)
        // `expense` beside the row, not `expenseId`, is what says a row can be opened.
        assertTrue(page.activities[0].expenseStillExists)
        assertFalse(page.activities[1].expenseStillExists)
    }

    // ---- rate limiting ------------------------------------------------------------------

    /** A clock the test moves by hand, so none of this waits for real time to pass. */
    private class FakeClock(var millis: Long = 0) {
        operator fun invoke(): Long = millis
    }

    private fun routeWholeGroup() = route(
        mapOf(
            "groups.get" to """{"group":${groupJson("g1", "Lisbon", participantJson("p1", "Ana"))}}""",
            "groups.expenses.list" to expensesListJson("", hasMore = false, nextCursor = 0),
            "groups.balances.list" to balancesListJson(""),
        ),
    )

    @Test
    fun `a second pull inside the window never reaches the server`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        routeWholeGroup()
        val clock = FakeClock()
        val viewModel = GroupDetailViewModel(
            groupId = "g1",
            recentGroupsStore = store,
            refreshLimiter = RefreshLimiter(5.seconds, clock::invoke),
        )

        viewModel.refresh()
        val afterLoad = server.requestCount

        // The first pull consumes the window.
        viewModel.refreshInPlace()
        val afterAllowed = server.requestCount
        assertTrue(afterAllowed > afterLoad, "the first pull should have fetched")

        clock.millis = 1_000
        viewModel.refreshInPlace()
        assertEquals(afterAllowed, server.requestCount, "the refused pull should fetch nothing")

        // And once the window has passed, pulling works again, a limiter that latched shut
        // would satisfy the assertion above and still be a bug.
        clock.millis = 10_000
        viewModel.refreshInPlace()
        assertTrue(server.requestCount > afterAllowed, "a pull after the window should fetch")
    }

    @Test
    fun `a refused pull does not leave the spinner turning`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        routeWholeGroup()
        val limiter = RefreshLimiter(5.seconds, FakeClock()::invoke)
        limiter.allow() // consumed, so the pull below is refused
        val viewModel = GroupDetailViewModel(
            groupId = "g1",
            recentGroupsStore = store,
            refreshLimiter = limiter,
        )

        viewModel.refreshInPlace()

        // PullToRefreshBox keeps its indicator up until the state says otherwise, so a dropped
        // refresh that left this true would spin over nothing until the user navigated away.
        assertFalse(viewModel.state.value.isRefreshing)
    }

    @Test
    fun `a write clears the limiter, so the reload it triggers is never dropped`() = runBlocking {
        val instance = server.url("/").toString()
        val store = FakeRecentGroupsStore(RecentGroupsSnapshot(groups = listOf(recentGroup("g1", instance))))
        routeWholeGroup()
        val limiter = RefreshLimiter(5.seconds, FakeClock()::invoke)
        limiter.allow()
        val viewModel = GroupDetailViewModel(
            groupId = "g1",
            recentGroupsStore = store,
            refreshLimiter = limiter,
        )
        viewModel.refresh()
        val before = server.requestCount

        // Editing an expense and immediately pulling must not be refused: the screen is known to
        // be stale, which is the case the window's reasoning does not cover.
        viewModel.applyExpenseChange()
        viewModel.refreshInPlace()

        assertTrue(server.requestCount > before)
    }

}

/** Two rows: one whose expense is still there, one whose expense has been deleted, which is the
 *  distinction an activity row's tappability turns on. */
private val activitiesJson = """
    {"activities":[
      {"id":"a1","groupId":"g1","time":"2025-06-02T10:00:00.000Z","activityType":"CREATE_EXPENSE",
       "participantId":"p1","expenseId":"e1","data":"Taxi",
       "expense":{"id":"e1","title":"Taxi Ride","amount":1000}},
      {"id":"a2","groupId":"g1","time":"2025-06-01T10:00:00.000Z","activityType":"DELETE_EXPENSE",
       "participantId":null,"expenseId":"e9","data":"Dinner","expense":null}
    ],"hasMore":false,"nextCursor":2}
""".trimIndent()

// ExpenseListItem's trailing `_count` field is a private property, Prisma's aggregate, per its
// own doc, so it (and everything after it) must be supplied positionally rather than by name.
private fun decodeExpense(id: String, expenseDate: String) = app.spliit.api.ExpenseListItem(
    id,
    id,
    100,
    Instant.parse(expenseDate),
    Instant.parse(expenseDate),
    false,
    app.spliit.api.SplitMode.EVENLY,
    null,
    null,
    app.spliit.api.Participant("p1", "Ana"),
    emptyList(),
    app.spliit.api.ExpenseListItem.Counts(0),
)
