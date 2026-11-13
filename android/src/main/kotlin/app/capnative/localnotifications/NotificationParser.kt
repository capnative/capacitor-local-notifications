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

    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val RESOURCE_PATTERN = Regex("^[a-z0-9_]{1,100}$")
    private val SOUND_FILE_PATTERN = Regex("^[A-Za-z0-9_-]{1,100}\\.[A-Za-z0-9]{1,8}$")
    private val GROUP_PATTERN = Regex("^[A-Za-z0-9_.-]{1,128}$")
    private val ISO_INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")
    private val COLOR_PATTERN = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val EVERY = setOf("year", "month", "two-weeks", "week", "day", "hour", "minute", "second")
    private val LEVELS = setOf("active", "critical", "passive", "timeSensitive")

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
        val group = optionalToken(value["group"], GROUP_PATTERN, "group", "INVALID_NOTIFICATION")
        val groupSummary = optionalBool(value["groupSummary"], false, "groupSummary", "INVALID_NOTIFICATION")
        if (groupSummary && group == null) {
            throw NotificationException("INVALID_NOTIFICATION", "groupSummary requires group.")
        }
        val ongoing = optionalBool(value["ongoing"], false, "ongoing", "INVALID_NOTIFICATION")
        val autoCancel = if (ongoing) false else optionalBool(value["autoCancel"], true, "autoCancel", "INVALID_NOTIFICATION")
        val actionTypeId = optionalToken(value["actionTypeId"], ID_PATTERN, "actionTypeId", "INVALID_NOTIFICATION")
        if (actionTypeId == "tap" || actionTypeId == "dismiss") {
            throw NotificationException("INVALID_ACTION", "actionTypeId cannot be tap or dismiss.")
        }
        return ValidatedNotification(
            id = id,
            title = title,
            body = body,
            largeBody = optionalText(value["largeBody"], 4000, "largeBody", "INVALID_NOTIFICATION"),
            summaryText = optionalText(value["summaryText"], 200, "summaryText", "INVALID_NOTIFICATION"),
            sound = parseSound(value["sound"]),
            smallIcon = optionalToken(value["smallIcon"], RESOURCE_PATTERN, "smallIcon", "INVALID_NOTIFICATION"),
            largeIcon = optionalToken(value["largeIcon"], RESOURCE_PATTERN, "largeIcon", "INVALID_NOTIFICATION"),
            iconColor = optionalColor(value["iconColor"], "iconColor", "INVALID_NOTIFICATION"),
            actionTypeId = actionTypeId,
            extraJson = parseExtra(value["extra"]),
            group = group,
            groupSummary = groupSummary,
            ongoing = ongoing,
            autoCancel = autoCancel,
            inbox = parseInbox(value["inboxList"]),
            allowWhileIdle = scheduled.allowWhileIdle,
            schedule = scheduled.echo,
            plan = scheduled.plan,
        )
    }

    fun parseActionTypes(value: Map<String, Any?>): List<ActionTypeSpec> {
        val types = value["types"] as? List<*> ?: throw NotificationException("INVALID_ACTION", "types must be an array of action types.")
        if (types.size > 20) throw NotificationException("INVALID_ACTION", "At most 20 action types can be registered.")
        val ids = HashSet<String>()
        return types.map { entry ->
            val map = asMap(entry) ?: throw NotificationException("INVALID_ACTION", "Each action type must be an object.")
            val id = requireToken(map["id"], ID_PATTERN, "id", "INVALID_ACTION")
            if (id == "tap" || id == "dismiss") {
                throw NotificationException("INVALID_ACTION", "Action type id cannot be tap or dismiss.")
            }
            if (!ids.add(id)) throw NotificationException("INVALID_ACTION", "Duplicate action type id $id.")
            ActionTypeSpec(id, parseActions(map["actions"]))
        }
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

    private data class ParsedSchedule(val echo: ScheduleEcho?, val plan: FirePlan, val allowWhileIdle: Boolean)

    private fun parseSchedule(value: Any?, now: Long, zone: TimeZone): ParsedSchedule {
        if (value == null) return ParsedSchedule(null, FirePlan.Immediate, false)
        val map = asMap(value) ?: throw NotificationException("INVALID_SCHEDULE", "schedule must be an object.")
        val allowWhileIdle = optionalBool(map["allowWhileIdle"], false, "allowWhileIdle", "INVALID_SCHEDULE")
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
                ScheduleEcho(every = unit.wire, count = count, allowWhileIdle = allowWhileIdle),
                FirePlan.Interval(unit, count, now, nextMs),
                allowWhileIdle,
            )
        }
        if (hasOn) {
            val on = parseCalendar(map["on"])
            val nextMs = ScheduleCalculator.nextCalendar(on, now, zone)
            return ParsedSchedule(
                ScheduleEcho(on = on, allowWhileIdle = allowWhileIdle),
                FirePlan.CalendarPlan(on, nextMs),
                allowWhileIdle,
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
                    ScheduleEcho(at = at, repeats = true, allowWhileIdle = allowWhileIdle),
                    FirePlan.FixedInterval(atMs, atMs - now, atMs),
                    allowWhileIdle,
                )
            }
            return ParsedSchedule(
                ScheduleEcho(at = at, allowWhileIdle = allowWhileIdle),
                FirePlan.Once(atMs),
                allowWhileIdle,
            )
        }
        val echo = if (allowWhileIdle) ScheduleEcho(allowWhileIdle = true) else null
        return ParsedSchedule(echo, FirePlan.Immediate, allowWhileIdle)
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

    private fun parseActions(value: Any?): List<ActionSpec> {
        if (value == null) return emptyList()
        val list = listOf(value) ?: throw NotificationException("INVALID_ACTION", "actions must be an array.")
        if (list.size > 3) throw NotificationException("INVALID_ACTION", "An action type can have at most 3 actions.")
        val ids = HashSet<String>()
        return list.map { entry ->
            val map = asMap(entry) ?: throw NotificationException("INVALID_ACTION", "Each action must be an object.")
            val id = requireToken(map["id"], ID_PATTERN, "id", "INVALID_ACTION")
            if (id == "tap" || id == "dismiss") throw NotificationException("INVALID_ACTION", "Action id cannot be tap or dismiss.")
            if (!ids.add(id)) throw NotificationException("INVALID_ACTION", "Duplicate action id $id.")
            ActionSpec(
                id = id,
                title = requireText(map["title"], 1, 40, "title", "INVALID_ACTION"),
                requiresAuthentication = optionalBool(map["requiresAuthentication"], false, "requiresAuthentication", "INVALID_ACTION"),
                foreground = optionalBool(map["foreground"], false, "foreground", "INVALID_ACTION"),
                destructive = optionalBool(map["destructive"], false, "destructive", "INVALID_ACTION"),
                input = optionalBool(map["input"], false, "input", "INVALID_ACTION"),
                inputButtonTitle = optionalText(map["inputButtonTitle"], 30, "inputButtonTitle", "INVALID_ACTION"),
                inputPlaceholder = optionalText(map["inputPlaceholder"], 60, "inputPlaceholder", "INVALID_ACTION"),
            )
        }
    }

    private fun parseInbox(value: Any?): List<String>? {
        if (value == null) return null
        val list = listOf(value) ?: throw NotificationException("INVALID_NOTIFICATION", "inboxList must contain 1 to 5 strings.")
        if (list.isEmpty() || list.size > 5) {
            throw NotificationException("INVALID_NOTIFICATION", "inboxList must contain 1 to 5 strings.")
        }
        return list.map { line ->
            val text = (line as? String)?.trim() ?: throw NotificationException("INVALID_NOTIFICATION", "inboxList entries must be strings.")
            if (text.isEmpty() || text.length > 200) {
                throw NotificationException("INVALID_NOTIFICATION", "Each inbox line must be 1 to 200 characters.")
            }
            text
        }
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

    private fun optionalColor(value: Any?, name: String, code: String): String? {
        if (value == null) return null
        val text = value as? String
        if (text == null || !COLOR_PATTERN.matches(text)) {
            throw NotificationException(code, "$name must be a #RGB, #RRGGBB, or #AARRGGBB color.")
        }
        return text
    }

    private fun optionalText(value: Any?, max: Int, name: String, code: String): String? {
        if (value == null) return null
        return requireText(value, 1, max, name, code)
    }

    private fun requireText(value: Any?, min: Int, max: Int, name: String, code: String): String {
        val text = (value as? String)?.trim() ?: throw NotificationException(code, "$name must be a string.")
        if (text.length < min || text.length > max) {
            throw NotificationException(code, "$name must be $min to $max characters.")
        }
        return text
    }

    private fun optionalToken(value: Any?, pattern: Regex, name: String, code: String): String? {
        if (value == null) return null
        return requireToken(value, pattern, name, code)
    }

    private fun requireToken(value: Any?, pattern: Regex, name: String, code: String): String {
        val text = value as? String
        if (text == null || !pattern.matches(text)) throw NotificationException(code, "$name has an invalid format.")
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
