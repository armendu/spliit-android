package app.spliit.android.feature.expense

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class RateStatusTest {
    private val dates = DateTimeFormatter.ISO_LOCAL_DATE
    private val day = LocalDate.of(2025, 6, 10)

    @Test
    fun `a rate the user typed has no status`() {
        assertNull(rateStatus(RateLookup.Found(day, day), rateIsCustom = true, dates))
    }

    @Test
    fun `a rate names the day it is for, and says when that is another day`() {
        assertEquals("Frankfurter's rate for 2025-06-10.", rateStatus(RateLookup.Found(day, day), false, dates))
        assertEquals(
            "Frankfurter's closest rate, from 2025-06-09.",
            rateStatus(RateLookup.Found(day.minusDays(1), day), false, dates),
        )
    }

    @Test
    fun `no rate and a failure both ask for one to be entered`() {
        assertEquals(
            "Frankfurter has no rate for this currency on that day. Enter one.",
            rateStatus(RateLookup.Unavailable, false, dates),
        )
        assertEquals(
            "Couldn't get a rate from Frankfurter. Enter one, or try again.",
            rateStatus(RateLookup.Failed, false, dates),
        )
    }
}
