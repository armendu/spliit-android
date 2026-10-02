package app.spliit.android.feature.group

import app.spliit.android.feature.groups.okBody
import app.spliit.api.TrpcClient
import app.spliit.core.LoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExpenseSearchTest {
    private val server = MockWebServer()

    @BeforeEach fun start() = server.start()
    @AfterEach fun stop() = server.close()

    private fun expensesJson(vararg ids: String): String {
        val rows = ids.joinToString(",") {
            """{"id":"$it","title":"$it","amount":100,"createdAt":"2025-06-01T00:00:00.000Z",
               "expenseDate":"2025-06-01T00:00:00.000Z","isReimbursement":false,"splitMode":"EVENLY",
               "recurrenceRule":"NONE","category":null,"paidBy":{"id":"p1","name":"Ana"},
               "paidFor":[],"_count":{"documents":0}}""".trimIndent()
        }
        return """{"expenses":[$rows],"hasMore":false,"nextCursor":0}"""
    }

    private fun searcher(state: MutableStateFlow<GroupDetailUiState>): ExpenseSearch {
        val client = TrpcClient(server.url("/").toString())
        return ExpenseSearch(
            groupId = "g1",
            pageSize = 20,
            state = state,
            scope = CoroutineScope(Dispatchers.Unconfined),
            client = { client },
        )
    }

    @Test
    fun `an answer to a question that has moved on is discarded`() = runBlocking {
        val state = MutableStateFlow(GroupDetailUiState())
        val search = searcher(state)
        search.onQueryChanged("taxi")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                state.value = state.value.copy(search = state.value.search.copy(query = "taxis"))
                return MockResponse.Builder().code(200).body(okBody(expensesJson("stale"))).build()
            }
        }

        search.run("taxi")

        val results = state.value.search.results
        assertTrue(
            results !is LoadState.Loaded,
            "the answer to 'taxi' must not become the answer to 'taxis'",
        )
    }

    @Test
    fun `a failed background reload leaves the results that are on screen`() = runBlocking {
        val state = MutableStateFlow(GroupDetailUiState())
        val search = searcher(state)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(okBody(expensesJson("e1"))).build()
        }
        search.onQueryChanged("taxi")
        search.run("taxi")
        assertEquals(listOf("e1"), (state.value.search.results as LoadState.Loaded).value.map { it.id })

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(500).body("boom").build()
        }
        search.reload(TrpcClient(server.url("/").toString()))

        val after = state.value.search.results
        assertTrue(after is LoadState.Loaded, "a failed reload should not replace the results")
        assertEquals(listOf("e1"), (after as LoadState.Loaded).value.map { it.id })
    }

    @Test
    fun `a failed search the user asked for does say so`() = runBlocking {
        val state = MutableStateFlow(GroupDetailUiState())
        val search = searcher(state)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(500).body("boom").build()
        }

        search.onQueryChanged("taxi")
        search.run("taxi")

        assertTrue(state.value.search.results is LoadState.Failed)
    }

    @Test
    fun `closing the field clears the query and the results`() = runBlocking {
        val state = MutableStateFlow(GroupDetailUiState())
        val search = searcher(state)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(okBody(expensesJson("e1"))).build()
        }
        search.onQueryChanged("taxi")
        search.run("taxi")

        search.setActive(false)

        assertEquals(SearchUiState(), state.value.search)
    }

    @Test
    fun `emptying the field drops the results without asking the server`() = runBlocking {
        val state = MutableStateFlow(GroupDetailUiState())
        val search = searcher(state)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(200).body(okBody(expensesJson("e1"))).build()
        }
        search.onQueryChanged("taxi")
        search.run("taxi")
        val asked = server.requestCount

        search.onQueryChanged("   ")

        assertNull(state.value.search.results)
        assertEquals(asked, server.requestCount, "an emptied field should not query the server")
        assertTrue(state.value.search.isActive)
    }
}
