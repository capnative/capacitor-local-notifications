package app.capnative.localnotifications

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

object NotificationParser {
    const val MAX_BATCH = 50
    const val MAX_PENDING = 100
    const val MAX_EXTRA_JSON = 4096
    const val INT32_MIN = -2147483648
    const val INT32_MAX = 2147483647

    private val SOUND_FILE_PATTERN = Regex("^[A-Za-z0-9_-]{1,100}\\.[A-Za-z0-9]{1,8}$")
    private val ISO_INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")
    private val EVERY = setOf("year", "month", "two-weeks", "week", "day", "hour", "minute", "second")

    fun parseNotifications(items: List<*>, now: Long, zone: TimeZone): List<ValidatedNotification> {
        if (items.isEmpty()) {
            throw NotificationException("INVALID_NOTIFICATION", "notifications must be a non-empty array.")
        }
        if (items.size > MAX_BATCH) {
            throw NotificationException("INVALID_NOTIFICATION", "At most $MAX_BATCH notifications can be scheduled in one call.")
        }
        val parsed = items.mapIndexed { index, item ->
            val map = asMap(item) ?: throw NotificationException("INVALID_NOTIFICATION", "Notification $index must be an object.")
            parseNotification(map, now, zone)
        }
        val ids = HashSet<Int>()
        for (notification in parsed) {
            if (!ids.add(notification.id)) {
                throw NotificationException("INVALID_NOTIFICATION", "Duplicate notification id ${notification.id} in one schedule() call.")
            }
        }
        return parsed
    }

    fun parseNotification(value: Map<String, Any?>, now: Long, zone: TimeZone): ValidatedNotification {
        val id = requireInt(value["id"], INT32_MIN, INT32_MAX, "id", "INVALID_NOTIFICATION")
        val title = requireText(value["title"], 1, 200, "title", "INVALID_NOTIFICATION")
        val body = requireText(value["body"], 0, 2000, "body", "INVALID_NOTIFICATION")
        val scheduled = parseSchedule(value["schedule"], now, zone)
        return ValidatedNotification(
            id = id,
            title = title,
            body = body,
            sound = parseSound(value["sound"]),
            extraJson = parseExtra(value["extra"]),
            schedule = scheduled.echo,
            plan = scheduled.plan,
        )
    }

    fun parseCancelIds(items: List<*>): List<Int> {
        if (items.isEmpty()) {
            throw NotificationException("INVALID_NOTIFICATION", "notifications must be a non-empty array of { id } objects.")
        }
        if (items.size > MAX_BATCH) {
            throw NotificationException("INVALID_NOTIFICATION", "At most $MAX_BATCH notifications can be cancelled in one call.")
        }
        return items.map { entry ->
            val map = asMap(entry) ?: throw NotificationException("INVALID_NOTIFICATION", "Each cancellation entry must be an object with an id.")
            requireInt(map["id"], INT32_MIN, INT32_MAX, "id", "INVALID_NOTIFICATION")
        }
    }

    fun asMap(value: Any?): Map<String, Any?>? {
        val source = when (value) {
            is Map<*, *> -> value
            is JSONObject -> jsonObjectToMap(value)
            else -> return null
        }
        val out = LinkedHashMap<String, Any?>()
        for ((key, child) in source) {
            if (key !is String) return null
            out[key] = unwrap(child)
        }
        return out
    }

    fun listOf(value: Any?): List<*>? = when (value) {
        is List<*> -> value.map { unwrap(it) }
        is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        else -> null
    }

    private data class ParsedSchedule(val echo: ScheduleEcho?, val plan: FirePlan)

    private fun parseSchedule(value: Any?, now: Long, zone: TimeZone): ParsedSchedule {
        if (value == null) return ParsedSchedule(null, FirePlan.Immediate)
        val map = asMap(value) ?: throw NotificationException("INVALID_SCHEDULE", "schedule must be an object.")
        val hasAt = map.containsKey("at") && map["at"] != null
        val hasOn = map.containsKey("on") && map["on"] != null
        val hasEvery = map.containsKey("every") && map["every"] != null
        if ((if (hasAt) 1 else 0) + (if (hasOn) 1 else 0) + (if (hasEvery) 1 else 0) > 1) {
            throw NotificationException("INVALID_SCHEDULE", "Use only one of schedule.at, schedule.on, or schedule.every.")
        }
        if (map["count"] != null && !hasEvery) {
            throw NotificationException("INVALID_SCHEDULE", "schedule.count is only valid together with schedule.every.")
        }
        if (map["repeats"] != null && !hasAt) {
            throw NotificationException("INVALID_SCHEDULE", "schedule.repeats is only valid together with schedule.at.")
        }
        if (hasEvery) {
            val everyWire = map["every"] as? String
            val unit = everyWire?.let { EveryUnit.fromWire(it) }
                ?: throw NotificationException("INVALID_SCHEDULE", "schedule.every must be year, month, two-weeks, week, day, hour, minute, or second.")
            if (!EVERY.contains(everyWire)) {
                throw NotificationException("INVALID_SCHEDULE", "schedule.every must be year, month, two-weeks, week, day, hour, minute, or second.")
            }
            val count = if (map["count"] == null) 1 else requireInt(map["count"], 1, 1000, "count", "INVALID_SCHEDULE")
            val nextMs = ScheduleCalculator.addEvery(now, unit, count, zone)
            val interval = nextMs - now
            if (interval < ScheduleCalculator.MIN_REPEAT_MS) {
                throw NotificationException("INVALID_SCHEDULE", "Repeating notifications must be at least 60 seconds apart.")
            }
            if (interval > ScheduleCalculator.MAX_LEAD_MS) {
                throw NotificationException("INVALID_SCHEDULE", "The first fire time must be within 8 years.")
            }
            return ParsedSchedule(
                ScheduleEcho(every = unit.wire, count = count),
                FirePlan.Interval(unit, count, now, nextMs),
            )
        }
        if (hasOn) {
            val on = parseCalendar(map["on"])
            val nextMs = ScheduleCalculator.nextCalendar(on, now, zone)
            return ParsedSchedule(
                ScheduleEcho(on = on),
                FirePlan.CalendarPlan(on, nextMs),
            )
        }
        if (hasAt) {
            val atMs = parseAt(map["at"])
            val repeats = optionalBool(map["repeats"], false, "repeats", "INVALID_SCHEDULE")
            if (atMs <= now) throw NotificationException("INVALID_SCHEDULE", "schedule.at must be in the future.")
            if (atMs - now > ScheduleCalculator.MAX_LEAD_MS) {
                throw NotificationException("INVALID_SCHEDULE", "schedule.at must be within 8 years.")
            }
            if (repeats && atMs - now < ScheduleCalculator.MIN_REPEAT_MS) {
                throw NotificationException("INVALID_SCHEDULE", "A repeating schedule.at must be at least 60 seconds in the future.")
            }
            val at = iso(atMs)
            if (repeats) {
                return ParsedSchedule(
                    ScheduleEcho(at = at, repeats = true),
                    FirePlan.FixedInterval(atMs, atMs - now, atMs),
                )
            }
            return ParsedSchedule(
                ScheduleEcho(at = at),
                FirePlan.Once(atMs),
            )
        }
        return ParsedSchedule(null, FirePlan.Immediate)
    }

    private fun parseCalendar(value: Any?): CalendarMatch {
        val map = asMap(value) ?: throw NotificationException("INVALID_SCHEDULE", "schedule.on must be an object.")
        val match = CalendarMatch(
            year = map["year"]?.let { requireInt(it, 1970, 2100, "year", "INVALID_SCHEDULE") },
            month = map["month"]?.let { requireInt(it, 1, 12, "month", "INVALID_SCHEDULE") },
            day = map["day"]?.let { requireInt(it, 1, 31, "day", "INVALID_SCHEDULE") },
            weekday = map["weekday"]?.let { requireInt(it, 1, 7, "weekday", "INVALID_SCHEDULE") },
            hour = map["hour"]?.let { requireInt(it, 0, 23, "hour", "INVALID_SCHEDULE") },
            minute = map["minute"]?.let { requireInt(it, 0, 59, "minute", "INVALID_SCHEDULE") },
            second = map["second"]?.let { requireInt(it, 0, 59, "second", "INVALID_SCHEDULE") },
        )
        if (match.year == null && match.month == null && match.day == null && match.weekday == null &&
            match.hour == null && match.minute == null && match.second == null
        ) {
            throw NotificationException("INVALID_SCHEDULE", "schedule.on must include at least one field.")
        }
        return match
    }

    private fun parseExtra(value: Any?): String? {
        if (value == null) return null
        val map = asMap(value) ?: throw NotificationException("INVALID_NOTIFICATION", "extra must be a plain object.")
        val sanitized = sanitize(map, 0, Counter())
        val encoded = encodeJson(sanitized)
        if (encoded.length > MAX_EXTRA_JSON) {
            throw NotificationException("INVALID_NOTIFICATION", "extra must be at most 4096 characters once encoded.")
        }
        return encoded
    }

    private class Counter(var count: Int = 0)

    private fun sanitize(value: Any?, depth: Int, counter: Counter): Any? {
        return when (value) {
            null -> null
            is String -> {
                if (value.length > 500) throw NotificationException("INVALID_NOTIFICATION", "Strings inside extra must be at most 500 characters.")
                value
            }
            is Boolean, is Int, is Long -> value
            is Double -> {
                if (!value.isFinite()) throw NotificationException("INVALID_NOTIFICATION", "extra cannot contain NaN or Infinity.")
                value
            }
            is Float -> {
                if (!value.isFinite()) throw NotificationException("INVALID_NOTIFICATION", "extra cannot contain NaN or Infinity.")
                value.toDouble()
            }
            is List<*> -> {
                if (depth >= 5 || value.size > 20) {
                    throw NotificationException("INVALID_NOTIFICATION", "extra is too deep or has a list longer than 20 items.")
                }
                value.map { sanitize(unwrap(it), depth + 1, counter) }
            }
            is Map<*, *> -> {
                if (depth >= 5) throw NotificationException("INVALID_NOTIFICATION", "extra is nested more than 5 levels.")
                val out = LinkedHashMap<String, Any?>()
                for ((key, child) in value) {
                    if (key !is String || key.isEmpty() || key.length > 64 || key == "__proto__" || key == "prototype" || key == "constructor") {
                        throw NotificationException("INVALID_NOTIFICATION", "extra contains a key that is not allowed.")
                    }
                    counter.count += 1
                    if (counter.count > 50) throw NotificationException("INVALID_NOTIFICATION", "extra can contain at most 50 keys.")
                    out[key] = sanitize(unwrap(child), depth + 1, counter)
                }
                out
            }
            else -> throw NotificationException("INVALID_NOTIFICATION", "extra only supports JSON values.")
        }
    }

    private fun encodeJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
            is Boolean -> if (value) "true" else "false"
            is Int, is Long -> value.toString()
            is Double -> if (value % 1.0 == 0.0 && value <= Long.MAX_VALUE && value >= Long.MIN_VALUE) value.toLong().toString() else value.toString()
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { encodeJson(it) }
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { entry ->
                encodeJson(entry.key as String) + ":" + encodeJson(entry.value)
            }
            else -> throw NotificationException("INVALID_NOTIFICATION", "extra only supports JSON values.")
        }
    }

    private fun parseSound(value: Any?): String? {
        if (value == null) return null
        val text = value as? String ?: throw NotificationException("INVALID_NOTIFICATION", "sound must be a string.")
        if (text == "" || text == "default") return text
        if (!SOUND_FILE_PATTERN.matches(text)) {
            throw NotificationException("INVALID_NOTIFICATION", "sound must be \"default\", an empty string, or a file name such as beep.wav.")
        }
        return text
    }

    fun parseAt(value: Any?): Long {
        when (value) {
            is Number -> {
                val ms = value.toLong()
                if (value.toDouble() != ms.toDouble()) {
                    throw NotificationException("INVALID_SCHEDULE", "schedule.at milliseconds must be an integer.")
                }
                return ms
            }
            is String -> {
                if (!ISO_INSTANT.matches(value)) {
                    throw NotificationException("INVALID_SCHEDULE", "schedule.at must be an ISO-8601 timestamp such as 2026-11-05T18:00:00.000Z.")
                }
                return parseInstant(normalizeIso(value))
                    ?: throw NotificationException("INVALID_SCHEDULE", "schedule.at is not a valid instant.")
            }
            else -> throw NotificationException("INVALID_SCHEDULE", "schedule.at must be an ISO-8601 timestamp such as 2026-11-05T18:00:00.000Z.")
        }
    }

    fun iso(ms: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(ms)
    }

    private fun normalizeIso(value: String): String {
        val match = Regex("^(.*T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,3}))?(Z|[+-]\\d{2}:\\d{2})$").matchEntire(value) ?: return value
        val fraction = (match.groupValues[2]).padEnd(3, '0')
        return match.groupValues[1] + "." + fraction + match.groupValues[3]
    }

    private fun parseInstant(value: String): Long? {
        val patterns = arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.isLenient = false
                return format.parse(value)?.time
            } catch (_: ParseException) {
                continue
            }
        }
        return null
    }

    private fun requireText(value: Any?, min: Int, max: Int, name: String, code: String): String {
        val text = (value as? String)?.trim() ?: throw NotificationException(code, "$name must be a string.")
        if (text.length < min || text.length > max) {
            throw NotificationException(code, "$name must be $min to $max characters.")
        }
        return text
    }

    private fun optionalBool(value: Any?, fallback: Boolean, name: String, code: String): Boolean {
        if (value == null) return fallback
        return value as? Boolean ?: throw NotificationException(code, "$name must be a boolean.")
    }

    private fun requireInt(value: Any?, min: Int, max: Int, name: String, code: String): Int {
        val number = when (value) {
            is Int -> value
            is Long -> if (value in INT32_MIN.toLong()..INT32_MAX.toLong()) value.toInt() else null
            is Double -> if (value % 1.0 == 0.0 && value >= min && value <= max) value.toInt() else null
            is Float -> if (value % 1.0f == 0.0f && value >= min && value <= max) value.toInt() else null
            else -> null
        } ?: throw NotificationException(code, "$name must be an integer from $min to $max.")
        if (number < min || number > max) {
            throw NotificationException(code, "$name must be an integer from $min to $max.")
        }
        return number
    }

    private fun unwrap(value: Any?): Any? {
        if (value == null || value == JSONObject.NULL) return null
        return when (value) {
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
            else -> value
        }
    }

    private fun jsonObjectToMap(value: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = unwrap(value.get(key))
        }
        return out
    }
}
