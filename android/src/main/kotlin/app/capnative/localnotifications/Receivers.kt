package app.capnative.localnotifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        NotificationPresenter.show(context, notification)
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

/** Reports a dismiss while the process is alive. Not exported. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.getStringExtra(Extras.MARKER) != Extras.MARKER_VALUE) return
        if (intent.getStringExtra(Extras.ACTION) != "dismiss") return
        if (intent.getIntExtra(Extras.ID, Int.MIN_VALUE) == Int.MIN_VALUE) return
        LocalNotificationsPlugin.deliverAction(intent)
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
