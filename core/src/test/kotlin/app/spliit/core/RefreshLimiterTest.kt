package app.spliit.core

import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshLimiterTest {

    /** A clock the test moves by hand, so none of this waits for real time to pass. */
    private class FakeClock(var millis: Long = 0) {
        operator fun invoke(): Long = millis
    }

    @Test
    fun `the first refresh is always allowed`() {
        val limiter = RefreshLimiter(5.seconds, FakeClock()::invoke)
        assertTrue(limiter.allow())
    }

    @Test
    fun `a second refresh inside the window is refused`() {
        val clock = FakeClock()
        val limiter = RefreshLimiter(5.seconds, clock::invoke)

        assertTrue(limiter.allow())
        clock.millis = 4_999
        assertFalse(limiter.allow())
    }

    @Test
    fun `the window is measured from the last allowed refresh, not the last attempt`() {
        val clock = FakeClock()
        val limiter = RefreshLimiter(5.seconds, clock::invoke)
        assertTrue(limiter.allow())

        // Someone tugging the list repeatedly. None of these may extend the window, or a
        // determined user would lock themselves out for as long as they kept pulling.
        clock.millis = 1_000
        assertFalse(limiter.allow())
        clock.millis = 3_000
        assertFalse(limiter.allow())

        clock.millis = 5_000
        assertTrue(limiter.allow())
    }

    @Test
    fun `exactly one interval later is allowed`() {
        val clock = FakeClock()
        val limiter = RefreshLimiter(5.seconds, clock::invoke)
        assertTrue(limiter.allow())
        clock.millis = 5_000
        assertTrue(limiter.allow())
    }

    @Test
    fun `reset lets the next refresh through immediately`() {
        val clock = FakeClock()
        val limiter = RefreshLimiter(5.seconds, clock::invoke)
        assertTrue(limiter.allow())
        clock.millis = 100
        assertFalse(limiter.allow())

        limiter.reset()
        assertTrue(limiter.allow())
    }

    @Test
    fun `a clock that jumps backwards does not lock the limiter out`() {
        // The wall clock can go backwards — a timezone-independent NTP correction, or the user
        // changing the time. Treating that as "negative elapsed time" would refuse every refresh
        // until the clock caught up again, which for a large correction is indefinitely.
        val clock = FakeClock(10_000)
        val limiter = RefreshLimiter(5.seconds, clock::invoke)
        assertTrue(limiter.allow())

        clock.millis = 1_000
        assertTrue(limiter.allow())
    }
}
