package app.spliit.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often a screen may ask the server again. Pull-to-refresh costs a round trip per section,
 * and neither end rate-limits, so idly tugging a list is several requests a second against what
 * may be somebody's Raspberry Pi.
 *
 * A minimum interval rather than a bucket, which would allow a burst then stop dead mid-gesture.
 * It covers the repeatable gestures only: first loads and reloads after a write happen once.
 *
 * @param now Injected so tests do not sleep. A wall-clock jump costs one extra or one skipped
 *   refresh, which is not worth a monotonic-clock dependency.
 */
public class RefreshLimiter(
    private val minInterval: Duration = DEFAULT_MIN_INTERVAL,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var lastAllowedAt: Long? = null

    /**
     * True when the caller may go to the server, and records that it did. Calling this twice is
     * not calling it once: asking without acting consumes the window for whoever asks next.
     */
    public fun allow(): Boolean = synchronized(lock) {
        val at = now()
        val last = lastAllowedAt
        if (last != null && at - last < minInterval.inWholeMilliseconds && at >= last) return false
        lastAllowedAt = at
        true
    }

    /**
     * Forgets the last refresh, so the next [allow] succeeds. For when the screen changed
     * underneath it, a different group or a write, and "you just asked" no longer holds.
     */
    public fun reset(): Unit = synchronized(lock) { lastAllowedAt = null }

    public companion object {
        /**
         * Long enough to stop a repeated gesture, short enough that nobody waiting on a change
         * someone else made notices it. Chosen rather than measured: there is no number here that
         * is correct, only one that is defensible.
         */
        public val DEFAULT_MIN_INTERVAL: Duration = 5.seconds
    }
}
