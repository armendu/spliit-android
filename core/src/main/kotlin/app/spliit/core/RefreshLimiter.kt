package app.spliit.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often a screen may ask the server again.
 *
 * Pull-to-refresh is a gesture people repeat, and it costs one round trip per section, so idly
 * tugging a list is several requests a second against what may be someone's Raspberry Pi.
 * Neither end rate-limits, which leaves the client.
 *
 * A minimum interval rather than a quota: a bucket allows a burst then stops dead mid-gesture,
 * where "pull twice quickly and the second one does not go" is something anyone can describe.
 *
 * It covers only the repeatable gestures, pull-to-refresh and retry. First loads, a tab opened
 * for the first time, and reloads after a write happen once and must not be dropped.
 *
 * @param now Injected so tests do not sleep. The wall clock can jump; a jump costs at most one
 *   extra or one skipped refresh, which is not worth a monotonic-clock dependency.
 */
public class RefreshLimiter(
    private val minInterval: Duration = DEFAULT_MIN_INTERVAL,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var lastAllowedAt: Long? = null

    /**
     * True when the caller may go to the server, and records that it did.
     *
     * Named for the fact that it has an effect: calling this twice is not the same as calling it
     * once, and a caller that asks without intending to act on the answer has consumed the
     * window for whoever asks next.
     */
    public fun allow(): Boolean = synchronized(lock) {
        val at = now()
        val last = lastAllowedAt
        if (last != null && at - last < minInterval.inWholeMilliseconds && at >= last) return false
        lastAllowedAt = at
        true
    }

    /**
     * Forgets the last refresh, so the next [allow] succeeds.
     *
     * For the cases where the screen's contents changed underneath the limiter and the usual
     * "you just asked" reasoning no longer holds, switching to a different group, or a write
     * that has to be read back.
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
