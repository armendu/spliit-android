package app.spliit.android.feature.group

import app.spliit.api.ExpenseListItem
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
        scope.launch { loadNextPage() }
    }

    suspend fun loadNextPage() {
        val search = state.value.search
        val shown = (search.results as? LoadState.Loaded)?.value ?: return
        if (!shown.hasMore || search.isLoadingMore) return
        val client = client() ?: return
        val query = search.query.trim()
        val from = shown.nextCursor
        state.update { it.copy(search = it.search.copy(isLoadingMore = true, loadMoreFailed = false)) }
        try {
            val page = page(client, query, from)
            state.update { current ->
                val latest = (current.search.results as? LoadState.Loaded)?.value
                if (isStale(query) || latest == null || latest.nextCursor != from) return@update current
                val merged = ExpensesPage(appendingNew(latest.expenses, page.expenses) { it.id }, page.hasMore, page.nextCursor)
                current.copy(search = current.search.copy(results = LoadState.Loaded(merged)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            if (!isStale(query)) state.update { it.copy(search = it.search.copy(loadMoreFailed = true)) }
        } finally {
            state.update { it.copy(search = it.search.copy(isLoadingMore = false)) }
        }
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
        return ExpensesPage(response.expenses.filter { it.matches(query) }, response.hasMore, response.nextCursor)
    }

    // A server older than the filter ignores it and sends every expense; zod drops keys it doesn't know.
    private fun ExpenseListItem.matches(query: String): Boolean = title.contains(query, ignoreCase = true)

    private fun isStale(query: String): Boolean = state.value.search.query.trim() != query

    private fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 250L

        // The server refuses a longer filter.
        const val MAX_QUERY_LENGTH = 200
    }
}
