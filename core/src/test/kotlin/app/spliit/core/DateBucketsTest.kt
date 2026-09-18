package app.spliit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A fixed clock, since bucketing must never consult the system clock or zone — see DateBuckets.kt. */
private fun clockAt(instant: String, zone: ZoneId): Clock =
    Clock.fixed(Instant.parse(instant), zone)

private val UTC: ZoneId = ZoneOffset.UTC

class DateBucketsTest {

    @Test
    fun `today`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.TODAY, DateBucket.of(Instant.parse("2025-06-15T01:00:00Z"), clock))
    }

    @Test
    fun `a future timestamp is today, not its own bucket`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.TODAY, DateBucket.of(Instant.parse("2025-06-20T00:00:00Z"), clock))
    }

    @Test
    fun `yesterday`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.YESTERDAY, DateBucket.of(Instant.parse("2025-06-14T23:00:00Z"), clock))
    }

    @Test
    fun `earlier this week`() {
        // 2025-06-15 is a Sunday, ISO week 24 (Mon 2025-06-09 .. Sun 2025-06-15).
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.EARLIER_THIS_WEEK, DateBucket.of(Instant.parse("2025-06-10T12:00:00Z"), clock))
    }

    @Test
    fun `last week`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.LAST_WEEK, DateBucket.of(Instant.parse("2025-06-05T12:00:00Z"), clock))
    }

    @Test
    fun `earlier this month, outside this and last week`() {
        val clock = clockAt("2025-06-25T12:00:00Z", UTC)
        assertEquals(DateBucket.EARLIER_THIS_MONTH, DateBucket.of(Instant.parse("2025-06-02T12:00:00Z"), clock))
    }

    @Test
    fun `last month`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.LAST_MONTH, DateBucket.of(Instant.parse("2025-05-10T12:00:00Z"), clock))
    }

    @Test
    fun `last month across a year boundary`() {
        val clock = clockAt("2025-01-15T12:00:00Z", UTC)
        assertEquals(DateBucket.LAST_MONTH, DateBucket.of(Instant.parse("2024-12-20T12:00:00Z"), clock))
    }

    @Test
    fun `earlier this year, outside this and last month`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.EARLIER_THIS_YEAR, DateBucket.of(Instant.parse("2025-02-10T12:00:00Z"), clock))
    }

    @Test
    fun `last year`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.LAST_YEAR, DateBucket.of(Instant.parse("2024-11-10T12:00:00Z"), clock))
    }

    @Test
    fun `older`() {
        val clock = clockAt("2025-06-15T12:00:00Z", UTC)
        assertEquals(DateBucket.OLDER, DateBucket.of(Instant.parse("2023-01-01T12:00:00Z"), clock))
    }

    // ---- the zone must actually be respected, not merely accepted ------------------------

    /**
     * Kiritimati (UTC+14) and Niue (UTC-11) are 25 hours apart — the widest spread of real IANA
     * zones. The same instant is "today" on one and "yesterday" on the other, which is exactly
     * the case a hard-coded UTC or system-default zone would get wrong.
     */
    @Test
    fun `the same instant buckets differently across a 25-hour zone gap`() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val niue = ZoneId.of("Pacific/Niue")

        // 2025-06-15T00:30:00Z is 2025-06-15T14:30 in Kiritimati but 2025-06-14T13:30 in Niue.
        val target = Instant.parse("2025-06-15T00:30:00Z")
        // "Now" is pinned to the same wall-clock instant in each zone, one minute later.
        val now = Instant.parse("2025-06-15T00:31:00Z")

        assertEquals(DateBucket.TODAY, DateBucket.of(target, Clock.fixed(now, kiritimati)))
        assertEquals(DateBucket.TODAY, DateBucket.of(target, Clock.fixed(now, niue)))

        // Now step "now" forward by a day in wall-clock terms for each zone independently: a
        // target that was "today" a moment ago must read "yesterday" once the local date has
        // rolled over in that zone, on each zone's own schedule.
        val aDayOfWallClockLater = now.plusSeconds(24 * 60 * 60)
        assertEquals(DateBucket.YESTERDAY, DateBucket.of(target, Clock.fixed(aDayOfWallClockLater, kiritimati)))
        assertEquals(DateBucket.YESTERDAY, DateBucket.of(target, Clock.fixed(aDayOfWallClockLater, niue)))
    }

    /**
     * The same UTC instant lands in different buckets under the two zones, at a fixed "now".
     *
     * now = 2025-06-15T04:00:00Z is 2025-06-15T18:00 local in Kiritimati (today = the 15th) and
     * 2025-06-14T17:00 local in Niue (today = the 14th) — the 25-hour gap already puts the two
     * zones a calendar day apart on "today" alone.
     *
     * target = 2025-06-13T10:30:00Z is 2025-06-14T00:30 local in Kiritimati — the 14th, one day
     * before Kiritimati's "today", so [DateBucket.YESTERDAY] there — and 2025-06-12T23:30 local
     * in Niue — the 12th, two days before Niue's "today" but still inside the same ISO week
     * (Mon the 9th .. Sun the 15th), so [DateBucket.EARLIER_THIS_WEEK] there. One instant, one
     * "now", two different answers — which is only possible if the zone is actually consulted.
     */
    @Test
    fun `zone changes which bucket a fixed instant falls into`() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val niue = ZoneId.of("Pacific/Niue")

        val now = Instant.parse("2025-06-15T04:00:00Z")
        val target = Instant.parse("2025-06-13T10:30:00Z")

        assertEquals(DateBucket.YESTERDAY, DateBucket.of(target, Clock.fixed(now, kiritimati)))
        assertEquals(DateBucket.EARLIER_THIS_WEEK, DateBucket.of(target, Clock.fixed(now, niue)))
    }
}
