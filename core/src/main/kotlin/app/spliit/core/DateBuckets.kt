package app.spliit.core

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.temporal.WeekFields

/**
 * The buckets an expense or activity list is divided into, newest first, one scheme for both:
 * an activity log wants the finer headings up top, because most of a log is from the last day or
 * two and "today"/"yesterday" is doing real work there, while an expense list is content with the
 * same headings even though its rows usually land in the coarser ones.
 *
 * Boundaries follow the **device's** time zone, not UTC's: an expense logged at 11pm local time
 * is "today" on the phone that logged it, whatever instant that maps to in UTC. That is why [of]
 * takes a [Clock] instead of reading [Clock.systemDefaultZone] itself, a function that reaches
 * for the system clock carries the machine's own zone silently, which is correct on every
 * developer's laptop and on a CI runner pinned to UTC alike, right up until a device set to a
 * different zone proves it was never actually zone-aware. Passing an explicit [Clock] (which
 * carries both the instant and the zone) is what lets a test pin two zones a day apart and prove
 * the boundary moves with the zone rather than being assumed.
 */
public enum class DateBucket {
    TODAY,
    YESTERDAY,
    EARLIER_THIS_WEEK,
    LAST_WEEK,
    EARLIER_THIS_MONTH,
    LAST_MONTH,
    EARLIER_THIS_YEAR,
    LAST_YEAR,
    OLDER,
    ;

    public companion object {
        /**
         * Which bucket [instant] falls into, as of [clock].
         *
         * A date that has not arrived yet, a clock-skewed phone, a recurring expense dated
         * ahead, lands in [TODAY] rather than a bucket of its own: this is a list of what has
         * happened, not a schedule of what hasn't, so the newest bucket is the honest place for
         * it.
         */
        public fun of(instant: Instant, clock: Clock): DateBucket {
            val zone = clock.zone
            val today = LocalDate.now(clock)
            val date = instant.atZone(zone).toLocalDate()

            if (!date.isBefore(today)) return TODAY
            if (date == today.minusDays(1)) return YESTERDAY

            val weekFields = WeekFields.ISO
            if (weekOf(date, weekFields) == weekOf(today, weekFields)) return EARLIER_THIS_WEEK
            if (weekOf(date, weekFields) == weekOf(today.minusWeeks(1), weekFields)) return LAST_WEEK

            val dateMonth = YearMonth.from(date)
            val todayMonth = YearMonth.from(today)
            if (dateMonth == todayMonth) return EARLIER_THIS_MONTH
            if (dateMonth == todayMonth.minusMonths(1)) return LAST_MONTH

            if (date.year == today.year) return EARLIER_THIS_YEAR
            if (date.year == today.year - 1) return LAST_YEAR

            return OLDER
        }

        /**
         * The (week-based year, week-of-year) pair for [date]. Comparing the pair, rather than
         * just [WeekFields.weekOfWeekBasedYear], is what makes a week straddling New Year's Eve
         * compare correctly, week 1 of one year and week 1 of the next both read "1" alone.
         */
        private fun weekOf(date: LocalDate, weekFields: WeekFields): Pair<Int, Int> =
            date.get(weekFields.weekBasedYear()) to date.get(weekFields.weekOfWeekBasedYear())
    }
}
