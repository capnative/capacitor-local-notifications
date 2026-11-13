package app.capnative.localnotifications

import org.json.JSONArray
import org.json.JSONObject

internal object NotificationCodec {
    fun notificationToJson(notification: StoredNotification): JSONObject {
        val json = JSONObject()
        json.put("id", notification.id)
        json.put("title", notification.title)
        json.put("body", notification.body)
        putOptional(json, "largeBody", notification.largeBody)
        putOptional(json, "summaryText", notification.summaryText)
        putOptional(json, "sound", notification.sound)
        putOptional(json, "smallIcon", notification.smallIcon)
        putOptional(json, "largeIcon", notification.largeIcon)
        putOptional(json, "iconColor", notification.iconColor)
        putOptional(json, "actionTypeId", notification.actionTypeId)
        putOptional(json, "extraJson", notification.extraJson)
        putOptional(json, "group", notification.group)
        json.put("groupSummary", notification.groupSummary)
        json.put("ongoing", notification.ongoing)
        json.put("autoCancel", notification.autoCancel)
        if (notification.inbox != null) {
            val inbox = JSONArray()
            notification.inbox.forEach { inbox.put(it) }
            json.put("inbox", inbox)
        }
        json.put("allowWhileIdle", notification.allowWhileIdle)
        notification.schedule?.let { json.put("schedule", scheduleToJson(it)) }
        json.put("plan", planToJson(notification.plan))
        return json
    }

    fun notificationFromJson(json: JSONObject): StoredNotification? {
        val plan = planFromJson(json.optJSONObject("plan")) ?: return null
        val inboxJson = json.optJSONArray("inbox")
        val inbox = inboxJson?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        }
        return StoredNotification(
            id = json.getInt("id"),
            title = json.getString("title"),
            body = json.getString("body"),
            largeBody = json.optStringOrNull("largeBody"),
            summaryText = json.optStringOrNull("summaryText"),
            sound = json.optStringOrNull("sound"),
            smallIcon = json.optStringOrNull("smallIcon"),
            largeIcon = json.optStringOrNull("largeIcon"),
            iconColor = json.optStringOrNull("iconColor"),
            actionTypeId = json.optStringOrNull("actionTypeId"),
            extraJson = json.optStringOrNull("extraJson"),
            group = json.optStringOrNull("group"),
            groupSummary = json.optBoolean("groupSummary", false),
            ongoing = json.optBoolean("ongoing", false),
            autoCancel = json.optBoolean("autoCancel", true),
            inbox = inbox,
            allowWhileIdle = json.optBoolean("allowWhileIdle", false),
            schedule = json.optJSONObject("schedule")?.let { scheduleFromJson(it) },
            plan = plan,
        )
    }

    fun actionsToJson(types: List<ActionTypeSpec>): JSONArray {
        val array = JSONArray()
        types.forEach { type ->
            val json = JSONObject()
            json.put("id", type.id)
            val actions = JSONArray()
            type.actions.forEach { action ->
                val item = JSONObject()
                item.put("id", action.id)
                item.put("title", action.title)
                item.put("requiresAuthentication", action.requiresAuthentication)
                item.put("foreground", action.foreground)
                item.put("destructive", action.destructive)
                item.put("input", action.input)
                putOptional(item, "inputButtonTitle", action.inputButtonTitle)
                putOptional(item, "inputPlaceholder", action.inputPlaceholder)
                actions.put(item)
            }
            json.put("actions", actions)
            array.put(json)
        }
        return array
    }

    fun actionsFromJson(array: JSONArray): List<ActionTypeSpec> {
        val types = ArrayList<ActionTypeSpec>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val actionsJson = json.optJSONArray("actions")
            val actions = ArrayList<ActionSpec>()
            if (actionsJson != null) {
                for (actionIndex in 0 until actionsJson.length()) {
                    val item = actionsJson.optJSONObject(actionIndex) ?: continue
                    val id = item.optStringOrNull("id") ?: continue
                    val title = item.optStringOrNull("title") ?: continue
                    actions.add(
                        ActionSpec(
                            id = id,
                            title = title,
                            requiresAuthentication = item.optBoolean("requiresAuthentication", false),
                            foreground = item.optBoolean("foreground", false),
                            destructive = item.optBoolean("destructive", false),
                            input = item.optBoolean("input", false),
                            inputButtonTitle = item.optStringOrNull("inputButtonTitle"),
                            inputPlaceholder = item.optStringOrNull("inputPlaceholder"),
                        ),
                    )
                }
            }
            val id = json.optStringOrNull("id") ?: continue
            types.add(ActionTypeSpec(id, actions))
        }
        return types
    }

    private fun scheduleToJson(schedule: ScheduleEcho): JSONObject {
        val json = JSONObject()
        putOptional(json, "at", schedule.at)
        if (schedule.repeats) json.put("repeats", true)
        if (schedule.allowWhileIdle) json.put("allowWhileIdle", true)
        putOptional(json, "every", schedule.every)
        schedule.count?.let { json.put("count", it) }
        schedule.on?.let { json.put("on", matchToJson(it)) }
        return json
    }

    private fun scheduleFromJson(json: JSONObject): ScheduleEcho {
        return ScheduleEcho(
            at = json.optStringOrNull("at"),
            repeats = json.optBoolean("repeats", false),
            allowWhileIdle = json.optBoolean("allowWhileIdle", false),
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
