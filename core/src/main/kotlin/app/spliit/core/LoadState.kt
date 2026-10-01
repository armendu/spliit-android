package app.spliit.core

public sealed interface LoadState<out T> {
    public data object Loading : LoadState<Nothing>

    public data class Loaded<T>(public val value: T) : LoadState<T>

    public data class Failed(public val message: String?) : LoadState<Nothing>
}
