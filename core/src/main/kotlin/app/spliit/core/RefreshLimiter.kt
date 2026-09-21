package app.spliit.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How often a screen is allowed to go and ask the server again.
 *
 * Pull-to-refresh is a gesture people repeat. It costs one round trip per section — the group,
 * a page of expenses, the balances, and the two lazy tabs if they have been opened — so a user
 * idly tugging a list is several requests a second against an instance that may be someone's
 * Raspberry Pi. Nothing in the app rate-limits on the way out and nothing in Spliit rate-limits
 * on the way in, which leaves the client as the only place this can be said.
 *
 * **A minimum interval rather than a quota.** A bucket of N refreshes per minute behaves
 * unpredictably at the edges — it allows a burst, then stops dead for a stretch the user cannot
 * predict, and the stop lands mid-gesture. One refresh per [minInterval] is the same protection
 * with behaviour anyone can describe: pull again and it goes, pull twice quickly and the second
 * one does not.
 *
 * **It does not apply to everything that fetches.** Opening a tab for the first time, a write
 * that invalidates what is on screen, and the first load of a screen are all things the user did
 * exactly once and must not be dropped. Only the repeatable gesture goes through here — see
 * `GroupDetailViewModel.pullToRefresh`. `retry` is deliberately also limited: a failure puts a
 * button on the screen, and a button next to an error is the other thing people tap repeatedly.
 *
 * @param now Injected so the tests do not sleep. Defaults to the wall clock, which is right here
 *   even though it can jump: the alternative, a monotonic clock, is not worth a dependency for a
 *   window this short, and a clock jump costs at most one extra or one skipped refresh.
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
     * "you just asked" reasoning no longer holds — switching to a different group, or a write
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
