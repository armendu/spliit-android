package app.spliit.core

/**
 * Where one request stands, so a screen can tell "there is nothing" apart from "nothing has
 * arrived yet".
 *
 * A group screen loads several things at once, the group, its expenses, its balances, and they
 * finish in whatever order the server answers. Driving an empty state off "this collection is
 * empty" alone would show a group with no expenses while the request for them is still on the
 * wire, and again if that request fails outright. Only a request that actually succeeded makes an
 * empty result mean anything, which is why [Loaded] carries the value rather than this type
 * collapsing to a plain boolean.
 *
 * Deliberately a sealed type here rather than the flag struct iOS uses, its `LoadState` tracks
 * `isLoading` and `hasLoaded` as independent booleans precisely so a refresh can keep showing
 * stale data while it runs. Nothing in this part's scope needs that overlap, and a sealed type is
 * a shape a screen's `when` can exhaust. If a "refreshing, but still showing what we had" state is
 * ever needed, it belongs as a flag on [Loaded], not as a fourth case that duplicates [Loading].
 */
public sealed interface LoadState<out T> {
    public data object Loading : LoadState<Nothing>

    public data class Loaded<T>(public val value: T) : LoadState<T>

    public data class Failed(public val message: String?) : LoadState<Nothing>
}
