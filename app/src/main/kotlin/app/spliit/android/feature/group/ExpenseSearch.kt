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

/**
 * The group screen's search field: the debounce, the request, and the stale-answer guard. Owns
 * the `search` slice of [GroupDetailUiState] and nothing else.
 *
 * @param client Null until the group's instance is resolved: nowhere to send a search yet.
 */
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

    /**
     * Answers [text] once the typing has stopped.
     *
     * Each call cancels the one before it, so the delay is only reached by the last keystroke.
     * Anyone typing faster than [DEBOUNCE_MILLIS] is cancelled inside the delay, before a request
     * goes out at all.
     */
    fun onQueryChanged(text: String) {
        state.update { it.copy(search = it.search.copy(isActive = true, query = text)) }
        cancel()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            // An emptied field is not a search for nothing: drop the results and show the prompt
            // rather than asking the server for the whole group again.
            state.update { it.copy(search = it.search.copy(results = null)) }
            return
        }
        job = scope.launch {
            delay(DEBOUNCE_MILLIS)
            run(trimmed)
        }
    }

    /** The request without the debounce, so a test can drive one without waiting out a timer. */
    suspend fun run(query: String) {
        val client = client() ?: return
        state.update { it.copy(search = it.search.copy(results = LoadState.Loading)) }
        fetch(client, query, onFailure = { message ->
            state.update { it.copy(search = it.search.copy(results = LoadState.Failed(message))) }
        })
    }

    /**
     * Re-runs whatever the field currently asks, for when an expense changed underneath it.
     *
     * Failures are swallowed rather than shown: the results on screen are a moment out of date
     * rather than wrong, and an error panel over them would be the worse of the two.
     */
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
            // The field may have moved on while this was in flight, and a stale page must not
            // become the answer to a question nobody asked.
            if (isStale(query)) return
            state.update { it.copy(search = it.search.copy(results = LoadState.Loaded(response.expenses))) }
        } catch (e: CancellationException) {
            // The next keystroke is already searching. Rethrown so this job dies as a cancelled
            // one; reporting it would flash "Couldn't search" between characters.
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
        /** How long a pause in typing counts as "done typing". */
        const val DEBOUNCE_MILLIS = 250L
    }
}
