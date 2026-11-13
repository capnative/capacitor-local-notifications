package app.capnative.localnotifications

import org.json.JSONObject

internal object NotificationCodec {
    fun notificationToJson(notification: StoredNotification): JSONObject {
        val json = JSONObject()
        json.put("id", notification.id)
        json.put("title", notification.title)
        json.put("body", notification.body)
        putOptional(json, "sound", notification.sound)
        putOptional(json, "extraJson", notification.extraJson)
        notification.schedule?.let { json.put("schedule", scheduleToJson(it)) }
        json.put("plan", planToJson(notification.plan))
        return json
    }

    fun notificationFromJson(json: JSONObject): StoredNotification? {
        val plan = planFromJson(json.optJSONObject("plan")) ?: return null
        return StoredNotification(
            id = json.getInt("id"),
            title = json.getString("title"),
            body = json.getString("body"),
            sound = json.optStringOrNull("sound"),
            extraJson = json.optStringOrNull("extraJson"),
            schedule = json.optJSONObject("schedule")?.let { scheduleFromJson(it) },
            plan = plan,
        )
    }

    private fun scheduleToJson(schedule: ScheduleEcho): JSONObject {
        val json = JSONObject()
        putOptional(json, "at", schedule.at)
        if (schedule.repeats) json.put("repeats", true)
        putOptional(json, "every", schedule.every)
        schedule.count?.let { json.put("count", it) }
        schedule.on?.let { json.put("on", matchToJson(it)) }
        return json
    }

    private fun scheduleFromJson(json: JSONObject): ScheduleEcho {
        return ScheduleEcho(
            at = json.optStringOrNull("at"),
            repeats = json.optBoolean("repeats", false),
            every = json.optStringOrNull("every"),
            count = if (json.has("count")) json.getInt("count") else null,
            on = json.optJSONObject("on")?.let { matchFromJson(it) },
        )
    }

    private fun matchToJson(match: CalendarMatch): JSONObject {
        val json = JSONObject()
        match.year?.let { json.put("year", it) }
        match.month?.let { json.put("month", it) }
        match.day?.let { json.put("day", it) }
        match.weekday?.let { json.put("weekday", it) }
        match.hour?.let { json.put("hour", it) }
        match.minute?.let { json.put("minute", it) }
        match.second?.let { json.put("second", it) }
        return json
    }

    private fun matchFromJson(json: JSONObject): CalendarMatch {
        return CalendarMatch(
            year = json.optIntOrNull("year"),
            month = json.optIntOrNull("month"),
            day = json.optIntOrNull("day"),
            weekday = json.optIntOrNull("weekday"),
            hour = json.optIntOrNull("hour"),
            minute = json.optIntOrNull("minute"),
            second = json.optIntOrNull("second"),
        )
    }

    private fun planToJson(plan: FirePlan): JSONObject {
        val json = JSONObject()
        when (plan) {
            is FirePlan.Immediate -> json.put("kind", "immediate")
            is FirePlan.Once -> {
                json.put("kind", "once")
                json.put("atMs", plan.atMs)
            }
            is FirePlan.FixedInterval -> {
                json.put("kind", "fixed-interval")
                json.put("anchorMs", plan.anchorMs)
                json.put("intervalMs", plan.intervalMs)
                json.put("nextMs", plan.nextMs)
            }
            is FirePlan.Interval -> {
                json.put("kind", "interval")
                json.put("unit", plan.unit.wire)
                json.put("count", plan.count)
                json.put("anchorMs", plan.anchorMs)
                json.put("nextMs", plan.nextMs)
            }
            is FirePlan.CalendarPlan -> {
                json.put("kind", "calendar")
                json.put("on", matchToJson(plan.on))
                json.put("nextMs", plan.nextMs)
            }
        }
        return json
    }

    private fun planFromJson(json: JSONObject?): FirePlan? {
        if (json == null) return null
        return when (json.optString("kind")) {
            "immediate" -> FirePlan.Immediate
            "once" -> FirePlan.Once(json.getLong("atMs"))
            "fixed-interval" -> FirePlan.FixedInterval(json.getLong("anchorMs"), json.getLong("intervalMs"), json.getLong("nextMs"))
            "interval" -> {
                val unit = EveryUnit.fromWire(json.getString("unit")) ?: return null
                FirePlan.Interval(unit, json.getInt("count"), json.getLong("anchorMs"), json.getLong("nextMs"))
            }
            "calendar" -> {
                val on = json.optJSONObject("on")?.let { matchFromJson(it) } ?: return null
                FirePlan.CalendarPlan(on, json.getLong("nextMs"))
            }
            else -> null
        }
    }

    private fun putOptional(json: JSONObject, key: String, value: String?) {
        if (value != null) json.put(key, value)
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return getString(key)
}

internal fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return getInt(key)
}
