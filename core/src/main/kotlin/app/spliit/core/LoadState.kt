package app.spliit.core

/**
 * Where one request stands, so a screen can tell "there is nothing" from "nothing has arrived
 * yet". Only a request that succeeded makes an empty result mean anything, which is why [Loaded]
 * carries the value instead of this collapsing to a boolean.
 *
 * A "refreshing, but still showing what we had" state belongs as a flag on [Loaded], not as a
 * fourth case duplicating [Loading].
 */
public sealed interface LoadState<out T> {
    public data object Loading : LoadState<Nothing>

    public data class Loaded<T>(public val value: T) : LoadState<T>

    public data class Failed(public val message: String?) : LoadState<Nothing>
}
