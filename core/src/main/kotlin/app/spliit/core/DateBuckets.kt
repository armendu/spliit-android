package app.spliit.core

import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.Instant
import java.time.temporal.WeekFields

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

        // Compare (week-based year, week), or a week spanning New Year compares wrong.
        private fun weekOf(date: LocalDate, weekFields: WeekFields): Pair<Int, Int> =
            date.get(weekFields.weekBasedYear()) to date.get(weekFields.weekOfWeekBasedYear())
    }
}
