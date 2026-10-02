package app.spliit.core

import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshLimiterTest {
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
        val clock = FakeClock(10_000)
        val limiter = RefreshLimiter(5.seconds, clock::invoke)
        assertTrue(limiter.allow())

        clock.millis = 1_000
        assertTrue(limiter.allow())
    }
}
