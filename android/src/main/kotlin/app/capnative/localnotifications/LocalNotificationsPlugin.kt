package app.capnative.localnotifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.util.ArrayDeque
import java.util.TimeZone

@CapacitorPlugin(
    name = "LocalNotifications",
    permissions = [
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = LocalNotificationsPlugin.DISPLAY),
    ],
)
class LocalNotificationsPlugin : Plugin() {
    override fun load() {
        instance = this
        NotificationPresenter.ensureDefaultChannel(context)
        consumeActivityIntent(activity?.intent)
    }

    override fun handleOnDestroy() {
        if (instance === this) instance = null
    }

    override fun handleOnNewIntent(intent: Intent) {
        super.handleOnNewIntent(intent)
        consumeActivityIntent(intent)
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        val result = JSObject()
        result.put("display", displayState())
        call.resolve(result)
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || getPermissionState(DISPLAY) == PermissionState.GRANTED) {
            checkPermissions(call)
            return
        }
        requestPermissionForAlias(DISPLAY, call, "displayPermissionCallback")
    }

    @PermissionCallback
    fun displayPermissionCallback(call: PluginCall) {
        checkPermissions(call)
    }

    @PluginMethod
    fun schedule(call: PluginCall) {
        try {
            val data = NotificationParser.asMap(call.data)
                ?: throw NotificationException("INVALID_NOTIFICATION", "schedule() expects an object with a notifications array.")
            val items = NotificationParser.listOf(data["notifications"])
                ?: throw NotificationException("INVALID_NOTIFICATION", "notifications must be a non-empty array.")
            val parsed = NotificationParser.parseNotifications(items, System.currentTimeMillis(), TimeZone.getDefault())
            if (displayState() != "granted") {
                call.reject("Notification permission is not granted.", "PERMISSION_DENIED")
                return
            }
            val store = NotificationStore(context)
            val actions = store.actionTypes()
            for (notification in parsed) {
                if (notification.actionTypeId != null && actions.none { it.id == notification.actionTypeId }) {
                    call.reject(
                        "Action type \"${notification.actionTypeId}\" is not registered. Call registerActionTypes() first.",
                        "INVALID_ACTION",
                    )
                    return
                }
                notification.smallIcon?.let { NotificationPresenter.requireIcon(context, it) }
                notification.largeIcon?.let { NotificationPresenter.requireIcon(context, it) }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && notification.sound != null) {
                    NotificationPresenter.requireLegacySound(context, notification.sound)
                }
            }
            val replacing = parsed.map { it.id }.toSet()
            val kept = store.all().count { it.id !in replacing }
            val incoming = parsed.count { it.plan !is FirePlan.Immediate }
            if (kept + incoming > NotificationParser.MAX_PENDING) {
                call.reject("At most ${NotificationParser.MAX_PENDING} notifications can be pending.", "INVALID_NOTIFICATION")
                return
            }
            val scheduler = AlarmScheduler(context)
            val ids = JSArray()
            for (notification in parsed) {
                val stored = notification.toStored()
                scheduler.cancel(stored.id)
                NotificationPresenter.cancel(context, stored.id)
                if (stored.plan is FirePlan.Immediate) {
                    store.remove(stored.id)
                    NotificationPresenter.show(context, stored, actions)
                    deliverReceived(stored)
                } else {
                    store.upsert(stored)
                    scheduler.schedule(stored)
                }
                val id = JSObject()
                id.put("id", stored.id)
                ids.put(id)
            }
            val result = JSObject()
            result.put("notifications", ids)
            call.resolve(result)
        } catch (error: NotificationException) {
            call.reject(error.message, error.code)
        }
    }

    @PluginMethod
    fun getPending(call: PluginCall) {
        val notifications = JSArray()
        for (notification in NotificationStore(context).all()) {
            if (notification.plan is FirePlan.Immediate) continue
            notifications.put(pendingJson(notification))
        }
        val result = JSObject()
        result.put("notifications", notifications)
        call.resolve(result)
    }

    @PluginMethod
    fun cancel(call: PluginCall) {
        try {
            val data = NotificationParser.asMap(call.data)
                ?: throw NotificationException("INVALID_NOTIFICATION", "notifications must be a non-empty array of { id } objects.")
            val items = NotificationParser.listOf(data["notifications"])
                ?: throw NotificationException("INVALID_NOTIFICATION", "notifications must be a non-empty array of { id } objects.")
            val ids = NotificationParser.parseCancelIds(items)
            val store = NotificationStore(context)
            val scheduler = AlarmScheduler(context)
            for (id in ids) {
                scheduler.cancel(id)
                NotificationPresenter.cancel(context, id)
                store.remove(id)
            }
            call.resolve()
        } catch (error: NotificationException) {
            call.reject(error.message, error.code)
        }
    }

    @PluginMethod
    fun getDeliveredNotifications(call: PluginCall) {
        val notifications = JSArray()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null) {
            for (active in manager.activeNotifications) {
                val extras = active.notification.extras
                if (extras.getString(Extras.MARKER) != Extras.MARKER_VALUE) continue
                val json = JSObject()
                json.put("id", extras.getInt(Extras.ID, active.id))
                json.put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
                json.put("body", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
                val group = active.notification.group
                if (group != null) json.put("group", group)
                json.put("groupSummary", (active.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0)
                extras.getString(Extras.EXTRA)?.let { extra ->
                    try {
                        json.put("extra", JSObject(extra))
                    } catch (_: Exception) {
                        // Ignore a payload that can no longer be parsed.
                    }
                }
                notifications.put(json)
            }
        }
        val result = JSObject()
        result.put("notifications", notifications)
        call.resolve(result)
    }

    @PluginMethod
    fun removeDeliveredNotifications(call: PluginCall) {
        try {
            val data = NotificationParser.asMap(call.data)
                ?: throw NotificationException("INVALID_NOTIFICATION", "notifications must be a non-empty array of { id } objects.")
            val items = NotificationParser.listOf(data["notifications"])
                ?: throw NotificationException("INVALID_NOTIFICATION", "notifications must be a non-empty array of { id } objects.")
            for (id in NotificationParser.parseCancelIds(items)) {
                NotificationPresenter.cancel(context, id)
            }
            call.resolve()
        } catch (error: NotificationException) {
            call.reject(error.message, error.code)
        }
    }

    @PluginMethod
    fun removeAllDeliveredNotifications(call: PluginCall) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null) {
            for (active in manager.activeNotifications) {
                if (active.notification.extras.getString(Extras.MARKER) == Extras.MARKER_VALUE) {
                    manager.cancel(active.tag, active.id)
                }
            }
        }
        call.resolve()
    }

    @PluginMethod
    fun registerActionTypes(call: PluginCall) {
        try {
            val data = NotificationParser.asMap(call.data)
                ?: throw NotificationException("INVALID_ACTION", "types must be an array of action types.")
            val types = NotificationParser.parseActionTypes(data)
            NotificationStore(context).saveActionTypes(types)
            call.resolve()
        } catch (error: NotificationException) {
            call.reject(error.message, error.code)
        }
    }

    private fun displayState(): String {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (enabled) "granted" else "denied"
        }
        return when (getPermissionState(DISPLAY)) {
            PermissionState.GRANTED -> if (enabled) "granted" else "denied"
            PermissionState.DENIED -> "denied"
            else -> if (enabled) "prompt" else "denied"
        }
    }

    private fun consumeActivityIntent(intent: Intent?) {
        if (intent?.getStringExtra(Extras.MARKER) != Extras.MARKER_VALUE) return
        val token = intent.getStringExtra(Extras.TOKEN) ?: return
        synchronized(seenTokens) {
            if (seenTokens.contains(token)) return
            seenTokens.addLast(token)
            while (seenTokens.size > 32) seenTokens.removeFirst()
        }
        intent.removeExtra(Extras.TOKEN)
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Extras.REMOTE_INPUT)
            ?.toString()
            ?.take(500)
            ?: intent.getStringExtra(Extras.INPUT)
        deliverAction(intent, input)
    }

    private fun pendingJson(notification: StoredNotification): JSObject {
        val json = JSObject()
        json.put("id", notification.id)
        json.put("title", notification.title)
        json.put("body", notification.body)
        notification.extraJson?.let { extra ->
            try {
                json.put("extra", JSObject(extra))
            } catch (_: Exception) {
                // Skip an extra payload that cannot be parsed.
            }
        }
        notification.schedule?.let { json.put("schedule", scheduleJson(it)) }
        return json
    }

    private fun scheduleJson(schedule: ScheduleEcho): JSObject {
        val json = JSObject()
        schedule.at?.let { json.put("at", it) }
        if (schedule.repeats) json.put("repeats", true)
        if (schedule.allowWhileIdle) json.put("allowWhileIdle", true)
        schedule.every?.let { json.put("every", it) }
        schedule.count?.let { json.put("count", it) }
        schedule.on?.let { match ->
            val on = JSObject()
            match.year?.let { on.put("year", it) }
            match.month?.let { on.put("month", it) }
            match.day?.let { on.put("day", it) }
            match.weekday?.let { on.put("weekday", it) }
            match.hour?.let { on.put("hour", it) }
            match.minute?.let { on.put("minute", it) }
            match.second?.let { on.put("second", it) }
            json.put("on", on)
        }
        return json
    }

    companion object {
        const val DISPLAY = "display"
        private val seenTokens = ArrayDeque<String>()

        @Volatile
        var instance: LocalNotificationsPlugin? = null

        fun deliverReceived(notification: StoredNotification) {
            val plugin = instance ?: return
            plugin.notifyListeners("localNotificationReceived", plugin.pendingJson(notification), true)
        }

        fun deliverAction(intent: Intent, input: String?): Boolean {
            val plugin = instance ?: return false
            val id = intent.getIntExtra(Extras.ID, Int.MIN_VALUE)
            if (id == Int.MIN_VALUE || intent.getStringExtra(Extras.MARKER) != Extras.MARKER_VALUE) return false
            val notification = JSObject()
            notification.put("id", id)
            notification.put("title", intent.getStringExtra(Extras.TITLE) ?: "")
            notification.put("body", intent.getStringExtra(Extras.BODY) ?: "")
            intent.getStringExtra(Extras.EXTRA)?.let { extra ->
                try {
                    notification.put("extra", JSObject(extra))
                } catch (_: Exception) {
                    // The extra payload is not valid JSON anymore.
                }
            }
            val action = JSObject()
            action.put("actionId", intent.getStringExtra(Extras.ACTION) ?: "tap")
            action.put("notification", notification)
            if (!input.isNullOrEmpty()) action.put("inputValue", input)
            plugin.notifyListeners("localNotificationActionPerformed", action, true)
            return true
        }
    }
}
