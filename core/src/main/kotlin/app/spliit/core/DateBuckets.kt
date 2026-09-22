package app.spliit.core

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.temporal.WeekFields

/**
 * The buckets an expense or activity list is divided into, newest first, one scheme for both.
 *
 * Boundaries follow the device's zone, not UTC: an expense logged at 11pm local is "today" on
 * the phone that logged it. [of] takes a [Clock] rather than reading the system one so that is
 * testable; a function that reaches for the system clock is zone-aware only by accident, and
 * looks correct on every laptop and on a UTC-pinned CI runner alike.
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
         * Which bucket [instant] falls into, as of [clock]. A future date, from clock skew or a
         * recurring expense, lands in [TODAY]: this is a list of what happened, not a schedule.
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
