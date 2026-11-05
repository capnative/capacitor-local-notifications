package app.capnative.localnotifications

import android.content.Context
import org.json.JSONArray

internal class NotificationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<StoredNotification> = synchronized(LOCK) { readNotifications() }

    fun get(id: Int): StoredNotification? = synchronized(LOCK) { readNotifications().firstOrNull { it.id == id } }

    fun upsert(notification: StoredNotification) = synchronized(LOCK) {
        val next = readNotifications().filterNot { it.id == notification.id } + notification
        writeNotifications(next)
    }

    fun remove(id: Int) = synchronized(LOCK) {
        writeNotifications(readNotifications().filterNot { it.id == id })
    }

    fun actionTypes(): List<ActionTypeSpec> = synchronized(LOCK) {
        val raw = preferences.getString(ACTIONS, null) ?: return emptyList()
        return try {
            NotificationCodec.actionsFromJson(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveActionTypes(types: List<ActionTypeSpec>) = synchronized(LOCK) {
        preferences.edit().putString(ACTIONS, NotificationCodec.actionsToJson(types).toString()).commit()
    }

    private fun readNotifications(): List<StoredNotification> {
        val raw = preferences.getString(NOTIFICATIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val items = ArrayList<StoredNotification>()
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val notification = try {
                    NotificationCodec.notificationFromJson(json)
                } catch (_: Exception) {
                    null
                }
                if (notification != null) items.add(notification)
            }
            items
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeNotifications(items: List<StoredNotification>) {
        val array = JSONArray()
        items.forEach { array.put(NotificationCodec.notificationToJson(it)) }
        preferences.edit().putString(NOTIFICATIONS, array.toString()).commit()
    }

    companion object {
        private const val PREFS = "app.capnative.localnotifications"
        private const val NOTIFICATIONS = "notifications"
        private const val ACTIONS = "actions"
        private val LOCK = Any()
    }
}
