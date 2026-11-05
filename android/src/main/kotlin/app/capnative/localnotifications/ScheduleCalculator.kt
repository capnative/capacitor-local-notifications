package app.capnative.localnotifications

import java.util.Calendar
import java.util.TimeZone

/**
 * Local-calendar scheduling. Month and year addition follow JavaScript Date
 * overflow (31 January plus one month lands in early March) so Android matches
 * the web implementation. Fixed units use a constant duration.
 */
object ScheduleCalculator {
    const val MIN_REPEAT_MS = 60_000L
    const val MAX_LEAD_MS = 8L * 366 * 24 * 60 * 60 * 1000

    fun nextCalendar(match: CalendarMatch, fromMs: Long, zone: TimeZone): Long {
        validateMatch(match)
        val mode = timeMode(match)
        if (mode.minuteWild) return nextSecond(fromMs, mode.second, zone)
        if (mode.hourWild) return nextMinute(fromMs, mode.minute, mode.second, zone)
        val limit = fromMs + MAX_LEAD_MS
        val day = Calendar.getInstance(zone)
        day.timeInMillis = fromMs
        day.set(Calendar.HOUR_OF_DAY, 0)
        day.set(Calendar.MINUTE, 0)
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)
        for (i in 0 until (366 * 8 + 3)) {
            val candidate = atLocalTime(day, mode.hour, mode.minute, mode.second)
            if (candidate != null && candidate > fromMs && dateFieldsMatch(candidate, match, zone)) {
                return candidate
            }
            day.add(Calendar.DATE, 1)
            if (day.timeInMillis > limit) break
        }
        throw NotificationException("INVALID_SCHEDULE", "No matching local time in the next 8 years.")
    }

    fun addEvery(fromMs: Long, unit: EveryUnit, count: Int, zone: TimeZone): Long {
        if (unit == EveryUnit.SECOND || unit == EveryUnit.MINUTE || unit == EveryUnit.HOUR) {
            val unitMs = when (unit) {
                EveryUnit.SECOND -> 1_000L
                EveryUnit.MINUTE -> 60_000L
                else -> 3_600_000L
            }
            return fromMs + count * unitMs
        }
        val date = Calendar.getInstance(zone)
        date.timeInMillis = fromMs
        when (unit) {
            EveryUnit.DAY -> date.add(Calendar.DATE, count)
            EveryUnit.WEEK -> date.add(Calendar.DATE, 7 * count)
            EveryUnit.TWO_WEEKS -> date.add(Calendar.DATE, 14 * count)
            EveryUnit.MONTH -> addMonthsOverflow(date, count)
            EveryUnit.YEAR -> addYearsOverflow(date, count)
            else -> throw NotificationException("INVALID_SCHEDULE", "Unknown schedule.every value.")
        }
        return date.timeInMillis
    }

    fun nextPlanAfter(plan: FirePlan, fromMs: Long, zone: TimeZone): FirePlan? {
        return when (plan) {
            is FirePlan.Immediate, is FirePlan.Once -> null
            is FirePlan.FixedInterval -> {
                if (fromMs < plan.anchorMs) return plan.copy(nextMs = plan.anchorMs)
                val steps = ((fromMs - plan.anchorMs) / plan.intervalMs) + 1
                plan.copy(nextMs = plan.anchorMs + steps * plan.intervalMs)
            }
            is FirePlan.Interval -> {
                if (fromMs < plan.anchorMs) return plan
                if (plan.unit == EveryUnit.SECOND || plan.unit == EveryUnit.MINUTE || plan.unit == EveryUnit.HOUR) {
                    val unitMs = when (plan.unit) {
                        EveryUnit.SECOND -> 1_000L
                        EveryUnit.MINUTE -> 60_000L
                        else -> 3_600_000L
                    }
                    val interval = plan.count * unitMs
                    val steps = ((fromMs - plan.anchorMs) / interval) + 1
                    return plan.copy(nextMs = plan.anchorMs + steps * interval)
                }
                var next = plan.anchorMs
                var guard = 0
                do {
                    next = addEvery(next, plan.unit, plan.count, zone)
                    guard += 1
                } while (next <= fromMs && guard < 20_000)
                if (next <= fromMs) {
                    throw NotificationException("INVALID_SCHEDULE", "The repeating schedule could not find a future time.")
                }
                plan.copy(nextMs = next)
            }
            is FirePlan.CalendarPlan -> plan.copy(nextMs = nextCalendar(plan.on, fromMs, zone))
        }
    }

    private fun addMonthsOverflow(date: Calendar, count: Int) {
        val day = date.get(Calendar.DAY_OF_MONTH)
        date.set(Calendar.DAY_OF_MONTH, 1)
        date.add(Calendar.MONTH, count)
        date.add(Calendar.DATE, day - 1)
    }

    private fun addYearsOverflow(date: Calendar, count: Int) {
        val month = date.get(Calendar.MONTH)
        val day = date.get(Calendar.DAY_OF_MONTH)
        date.set(Calendar.DAY_OF_MONTH, 1)
        date.add(Calendar.YEAR, count)
        date.set(Calendar.MONTH, month)
        date.add(Calendar.DATE, day - 1)
    }

    private data class TimeMode(
        val minuteWild: Boolean,
        val hourWild: Boolean,
        val hour: Int,
        val minute: Int,
        val second: Int,
    )

    private fun timeMode(match: CalendarMatch): TimeMode {
        val anyDate = match.year != null || match.month != null || match.day != null || match.weekday != null
        val minuteWild = match.minute == null && match.hour == null && match.second != null && !anyDate
        val hourWild = match.hour == null && (match.minute != null || match.second != null) && !anyDate
        return TimeMode(
            minuteWild,
            hourWild,
            match.hour ?: 0,
            match.minute ?: 0,
            match.second ?: 0,
        )
    }

    private fun validateMatch(match: CalendarMatch) {
        if (match.year == null && match.month == null && match.day == null && match.weekday == null &&
            match.hour == null && match.minute == null && match.second == null
        ) {
            throw NotificationException("INVALID_SCHEDULE", "schedule.on must include at least one field.")
        }
    }

    private fun nextSecond(fromMs: Long, second: Int, zone: TimeZone): Long {
        val date = Calendar.getInstance(zone)
        date.timeInMillis = fromMs
        date.set(Calendar.MILLISECOND, 0)
        date.set(Calendar.SECOND, second)
        if (date.timeInMillis <= fromMs || date.get(Calendar.SECOND) != second) {
            date.add(Calendar.MINUTE, 1)
            date.set(Calendar.SECOND, second)
            date.set(Calendar.MILLISECOND, 0)
        }
        return date.timeInMillis
    }

    private fun nextMinute(fromMs: Long, minute: Int, second: Int, zone: TimeZone): Long {
        val date = Calendar.getInstance(zone)
        date.timeInMillis = fromMs
        date.set(Calendar.MILLISECOND, 0)
        date.set(Calendar.SECOND, second)
        date.set(Calendar.MINUTE, minute)
        if (date.timeInMillis <= fromMs || date.get(Calendar.MINUTE) != minute || date.get(Calendar.SECOND) != second) {
            date.add(Calendar.HOUR_OF_DAY, 1)
            date.set(Calendar.MINUTE, minute)
            date.set(Calendar.SECOND, second)
            date.set(Calendar.MILLISECOND, 0)
        }
        return date.timeInMillis
    }

    private fun atLocalTime(day: Calendar, hour: Int, minute: Int, second: Int): Long? {
        val date = day.clone() as Calendar
        date.set(Calendar.HOUR_OF_DAY, hour)
        date.set(Calendar.MINUTE, minute)
        date.set(Calendar.SECOND, second)
        date.set(Calendar.MILLISECOND, 0)
        if (date.get(Calendar.YEAR) != day.get(Calendar.YEAR) ||
            date.get(Calendar.DAY_OF_YEAR) != day.get(Calendar.DAY_OF_YEAR) ||
            date.get(Calendar.HOUR_OF_DAY) != hour ||
            date.get(Calendar.MINUTE) != minute ||
            date.get(Calendar.SECOND) != second
        ) {
            return null
        }
        return date.timeInMillis
    }

    private fun dateFieldsMatch(ms: Long, match: CalendarMatch, zone: TimeZone): Boolean {
        val date = Calendar.getInstance(zone)
        date.timeInMillis = ms
        if (match.year != null && date.get(Calendar.YEAR) != match.year) return false
        if (match.month != null && date.get(Calendar.MONTH) + 1 != match.month) return false
        if (match.day != null && date.get(Calendar.DAY_OF_MONTH) != match.day) return false
        if (match.weekday != null && date.get(Calendar.DAY_OF_WEEK) != match.weekday) return false
        return true
    }
}
