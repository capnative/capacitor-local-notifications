package app.capnative.localnotifications

class NotificationException(val code: String, message: String) : Exception(message)

enum class EveryUnit(val wire: String) {
    YEAR("year"),
    MONTH("month"),
    TWO_WEEKS("two-weeks"),
    WEEK("week"),
    DAY("day"),
    HOUR("hour"),
    MINUTE("minute"),
    SECOND("second");

    companion object {
        fun fromWire(value: String): EveryUnit? = entries.firstOrNull { it.wire == value }
    }
}

data class CalendarMatch(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val weekday: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val second: Int? = null,
)

sealed class FirePlan {
    abstract val nextAtMs: Long?

    data object Immediate : FirePlan() {
        override val nextAtMs: Long? = null
    }

    data class Once(val atMs: Long) : FirePlan() {
        override val nextAtMs: Long = atMs
    }

    data class FixedInterval(val anchorMs: Long, val intervalMs: Long, val nextMs: Long) : FirePlan() {
        override val nextAtMs: Long = nextMs
    }

    data class Interval(val unit: EveryUnit, val count: Int, val anchorMs: Long, val nextMs: Long) : FirePlan() {
        override val nextAtMs: Long = nextMs
    }

    data class CalendarPlan(val on: CalendarMatch, val nextMs: Long) : FirePlan() {
        override val nextAtMs: Long = nextMs
    }
}

data class ScheduleEcho(
    val at: String? = null,
    val repeats: Boolean = false,
    val every: String? = null,
    val count: Int? = null,
    val on: CalendarMatch? = null,
)

data class ValidatedNotification(
    val id: Int,
    val title: String,
    val body: String,
    val sound: String? = null,
    val extraJson: String? = null,
    val schedule: ScheduleEcho? = null,
    val plan: FirePlan,
)

data class StoredNotification(
    val id: Int,
    val title: String,
    val body: String,
    val sound: String? = null,
    val extraJson: String? = null,
    val schedule: ScheduleEcho? = null,
    val plan: FirePlan,
)

fun ValidatedNotification.toStored(): StoredNotification = StoredNotification(
    id, title, body, sound, extraJson, schedule, plan,
)
