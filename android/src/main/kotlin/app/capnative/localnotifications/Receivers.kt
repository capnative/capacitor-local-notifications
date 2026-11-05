package app.capnative.localnotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Delivers a scheduled notification. Extras are only the id; the payload is read
 * from app-private storage. The receiver is not exported.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Extras.ACTION_FIRE) return
        val id = intent.getIntExtra(Extras.ID, Int.MIN_VALUE)
        if (id == Int.MIN_VALUE) return
        val store = NotificationStore(context)
        val notification = store.get(id) ?: return
        NotificationPresenter.show(context, notification, store.actionTypes())
        val next = NotificationRestorer.advanceAfterDelivery(context, notification)
        if (next == null) {
            store.remove(id)
        } else {
            store.upsert(next)
            AlarmScheduler(context).schedule(next)
        }
        LocalNotificationsPlugin.deliverReceived(notification)
    }
}

/**
 * Handles action buttons and dismiss. Not exported. A dismiss does not bring
 * the app forward; other actions do when the process is not already running.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.getStringExtra(Extras.MARKER) != Extras.MARKER_VALUE) return
        val actionId = intent.getStringExtra(Extras.ACTION) ?: return
        val id = intent.getIntExtra(Extras.ID, Int.MIN_VALUE)
        if (id == Int.MIN_VALUE) return
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Extras.REMOTE_INPUT)
            ?.toString()
            ?.take(500)
        if (actionId == "dismiss") {
            LocalNotificationsPlugin.deliverAction(intent, input)
            return
        }
        val pluginAlive = LocalNotificationsPlugin.deliverAction(intent, input)
        val foreground = intent.getBooleanExtra(Extras.FOREGROUND, false) || actionId == "tap"
        if (!pluginAlive || foreground) {
            val launch = launchIntent(context)
            if (!pluginAlive) {
                launch.putExtras(intent.extras ?: return)
                launch.putExtra(Extras.TOKEN, java.util.UUID.randomUUID().toString())
                if (input != null) launch.putExtra(Extras.INPUT, input)
            }
            context.startActivity(launch)
        }
    }
}

/** Reschedules alarms after reboot. Intent extras are ignored. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        NotificationRestorer.restore(context)
    }
}

/** Re-arms alarms after the user changes the exact-alarm setting. */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") return
        NotificationRestorer.restore(context)
    }
}
