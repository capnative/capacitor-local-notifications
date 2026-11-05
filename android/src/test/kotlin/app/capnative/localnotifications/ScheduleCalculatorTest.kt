package app.capnative.localnotifications

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleCalculatorTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = parse("2026-11-05T15:30:45.000Z")

    @Test
    fun rollsFixedLocalTimeToTheNextDay() {
        assertEquals("2026-11-06T09:00:00.000Z", iso(ScheduleCalculator.nextCalendar(CalendarMatch(hour = 9), now, utc)))
        assertEquals("2026-11-05T16:00:00.000Z", iso(ScheduleCalculator.nextCalendar(CalendarMatch(hour = 16), now, utc)))
    }

    @Test
    fun treatsOmittedHourAsWildcardWhenOnlyMinuteIsSet() {
        assertEquals("2026-11-05T16:15:00.000Z", iso(ScheduleCalculator.nextCalendar(CalendarMatch(minute = 15), now, utc)))
        assertEquals("2026-11-05T15:31:10.000Z", iso(ScheduleCalculator.nextCalendar(CalendarMatch(second = 10), now, utc)))
        assertEquals("2026-11-05T15:30:50.000Z", iso(ScheduleCalculator.nextCalendar(CalendarMatch(second = 50), now, utc)))
    }

    @Test
    fun matchesWeekdayAndDayOfMonth() {
        assertEquals(
            "2026-11-09T09:00:00.000Z",
            iso(ScheduleCalculator.nextCalendar(CalendarMatch(weekday = 2, hour = 9, minute = 0), now, utc)),
        )
        assertEquals("2026-12-31T10:00:00.000Z", iso(ScheduleCalculator.nextCalendar(CalendarMatch(day = 31, hour = 10), now, utc)))
        assertEquals(
            "2027-01-01T00:00:00.000Z",
            iso(ScheduleCalculator.nextCalendar(CalendarMatch(year = 2027, month = 1, day = 1), now, utc)),
        )
    }

    @Test
    fun rejectsImpossibleDate() {
        val error = runCatching {
            ScheduleCalculator.nextCalendar(CalendarMatch(year = 2027, month = 2, day = 31), now, utc)
        }.exceptionOrNull() as NotificationException
        assertEquals("INVALID_SCHEDULE", error.code)
    }

    @Test
    fun monthAdditionMatchesJavaScriptOverflow() {
        val start = parse("2026-01-31T00:00:00.000Z")
        assertEquals("2026-03-03T00:00:00.000Z", iso(ScheduleCalculator.addEvery(start, EveryUnit.MONTH, 1, utc)))
        assertEquals("2027-01-31T00:00:00.000Z", iso(ScheduleCalculator.addEvery(start, EveryUnit.YEAR, 1, utc)))
    }

    @Test
    fun fixedIntervalKeepsPhase() {
        val at = now + 120_000
        val plan = FirePlan.FixedInterval(at, 120_000, at)
        val next = ScheduleCalculator.nextPlanAfter(plan, at, utc) as FirePlan.FixedInterval
        assertEquals(at + 120_000, next.nextMs)
    }

    @Test
    fun skipsMissingDaylightSavingHour() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val beforeGap = parseOffset("2026-03-08T01:00:00-05:00")
        val next = ScheduleCalculator.nextCalendar(CalendarMatch(hour = 2, minute = 30), beforeGap, zone)
        assertEquals("2026-03-09T02:30:00-04:00", isoOffset(next, zone))
    }

    @Test
    fun oneShotDoesNotRepeat() {
        assertNull(ScheduleCalculator.nextPlanAfter(FirePlan.Once(now + 1_000), now + 1_000, utc))
    }

    private fun parse(value: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = utc
        return format.parse(value)!!.time
    }

    private fun parseOffset(value: String): Long {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        format.timeZone = utc
        return format.parse(value)!!.time
    }

    private fun iso(ms: Long): String = NotificationParser.iso(ms)

    private fun isoOffset(ms: Long, zone: TimeZone): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        format.timeZone = zone
        return format.format(ms)
    }
}
