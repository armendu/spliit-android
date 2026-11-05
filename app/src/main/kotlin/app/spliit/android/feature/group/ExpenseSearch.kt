package app.spliit.android.feature.group

import app.spliit.api.SpliitEndpoints
import app.spliit.api.TrpcClient
import app.spliit.api.TrpcException
import app.spliit.core.LoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ExpenseSearch(
    private val groupId: String,
    private val pageSize: Int,
    private val state: MutableStateFlow<GroupDetailUiState>,
    private val scope: CoroutineScope,
    private val client: () -> TrpcClient?,
) {
    private var job: Job? = null
    private var moreJob: Job? = null

    fun setActive(active: Boolean) {
        if (active) {
            state.update { it.copy(search = it.search.copy(isActive = true)) }
        } else {
            cancel()
            state.update { it.copy(search = SearchUiState()) }
        }
    }

    fun onQueryChanged(typed: String) {
        val text = typed.take(MAX_QUERY_LENGTH)
        state.update { it.copy(search = it.search.copy(isActive = true, query = text)) }
        cancel()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            state.update { it.copy(search = it.search.copy(results = null)) }
            return
        }
        job = scope.launch {
            delay(DEBOUNCE_MILLIS)
            run(trimmed)
        }
    }

    suspend fun run(query: String) {
        val client = client() ?: return
        state.update { it.copy(search = it.search.copy(results = LoadState.Loading)) }
        fetch(client, query, onFailure = { message ->
            state.update { it.copy(search = it.search.copy(results = LoadState.Failed(message))) }
        })
    }

    suspend fun reload(client: TrpcClient) {
        val query = state.value.search.query.trim()
        if (query.isEmpty()) return
        fetch(client, query, onFailure = {})
    }

    fun loadMore() {
        moreJob = scope.launch { loadNextPage() }
    }

    suspend fun loadNextPage() {
        val search = state.value.search
        val query = search.query.trim()
        fetchNextPage(
            current = (search.results as? LoadState.Loaded)?.value,
            isLoading = search.isLoadingMore,
            client = client,
            setLoading = { loading ->
                state.update { it.copy(search = it.search.copy(isLoadingMore = loading, loadMoreFailed = false)) }
            },
            setFailed = {
                if (!isStale(query)) state.update { it.copy(search = it.search.copy(loadMoreFailed = true)) }
            },
            fetch = { client, cursor -> page(client, query, cursor) },
            publish = { fetched, from ->
                state.update { current ->
                    val latest = (current.search.results as? LoadState.Loaded)?.value
                    if (isStale(query) || latest == null || latest.nextCursor != from) return@update current
                    val merged = ExpensesPage(
                        appendingNew(latest.expenses, fetched.items) { it.id },
                        fetched.hasMore,
                        fetched.nextCursor,
                    )
                    current.copy(search = current.search.copy(results = LoadState.Loaded(merged)))
                }
            },
        )
    }

    private suspend fun fetch(client: TrpcClient, query: String, onFailure: (String?) -> Unit) {
        try {
            val page = page(client, query, cursor = 0)
            if (isStale(query)) return
            state.update {
                it.copy(search = it.search.copy(results = LoadState.Loaded(page), loadMoreFailed = false))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            if (!isStale(query)) onFailure(e.message)
        }
    }

    private suspend fun page(client: TrpcClient, query: String, cursor: Int): ExpensesPage {
        val response = client.call(SpliitEndpoints.expensesList(groupId, cursor, pageSize, filter = query))
        // Not narrowed again here: newer servers also match the notes, which the list doesn't carry.
        return ExpensesPage(response.expenses, response.hasMore, response.nextCursor)
    }

    private fun isStale(query: String): Boolean = state.value.search.query.trim() != query

    private fun cancel() {
        job?.cancel()
        job = null
        moreJob?.cancel()
        moreJob = null
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 250L

        // The server refuses a longer filter.
        const val MAX_QUERY_LENGTH = 200
    }
}
