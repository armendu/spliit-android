package app.spliit.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public class RefreshLimiter(
    private val minInterval: Duration = DEFAULT_MIN_INTERVAL,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var lastAllowedAt: Long? = null

    // Records the refresh too: don't call it just to check.
    public fun allow(): Boolean = synchronized(lock) {
        val at = now()
        val last = lastAllowedAt
        if (last != null && at - last < minInterval.inWholeMilliseconds && at >= last) return false
        lastAllowedAt = at
        true
    }

    public fun reset(): Unit = synchronized(lock) { lastAllowedAt = null }

    public companion object {
        public val DEFAULT_MIN_INTERVAL: Duration = 5.seconds
    }
}
