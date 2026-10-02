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

    fun setActive(active: Boolean) {
        if (active) {
            state.update { it.copy(search = it.search.copy(isActive = true)) }
        } else {
            cancel()
            state.update { it.copy(search = SearchUiState()) }
        }
    }

    fun onQueryChanged(text: String) {
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

    private suspend fun fetch(client: TrpcClient, query: String, onFailure: (String?) -> Unit) {
        try {
            val response = client.call(
                SpliitEndpoints.expensesList(groupId, cursor = 0, limit = pageSize, filter = query),
            )
            if (isStale(query)) return
            state.update { it.copy(search = it.search.copy(results = LoadState.Loaded(response.expenses))) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrpcException) {
            if (!isStale(query)) onFailure(e.message)
        }
    }

    private fun isStale(query: String): Boolean = state.value.search.query.trim() != query

    private fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 250L
    }
}
