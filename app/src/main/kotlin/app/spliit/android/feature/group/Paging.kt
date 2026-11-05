package app.spliit.android.feature.group

import app.spliit.api.TrpcClient
import app.spliit.api.TrpcException
import kotlinx.coroutines.CancellationException

// publish merges into the state as it is when the page lands, and drops the page if the list moved
// (a delete, a reload, a new query) while it was in flight: its offset no longer lines up with what's shown.
internal suspend fun <T> fetchNextPage(
    current: CursorPage<T>?,
    isLoading: Boolean,
    client: () -> TrpcClient?,
    setLoading: (Boolean) -> Unit,
    setFailed: () -> Unit,
    fetch: suspend (TrpcClient, Int) -> CursorPage<T>,
    publish: (fetched: CursorPage<T>, from: Int) -> Unit,
) {
    if (current == null || !current.hasMore || isLoading) return
    val resolved = client() ?: return

    setLoading(true)
    var failed = false
    try {
        publish(fetch(resolved, current.nextCursor), current.nextCursor)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TrpcException) {
        failed = true
    } finally {
        setLoading(false)
    }
    if (failed) setFailed()
}

fun <T> appendingNew(shown: List<T>, more: List<T>, idOf: (T) -> String): List<T> {
    val known = shown.mapTo(HashSet(), idOf)
    return shown + more.filterNot { idOf(it) in known }
}
