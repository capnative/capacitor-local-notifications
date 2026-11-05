package app.capnative.localnotifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import java.util.TimeZone

internal class AlarmScheduler(private val context: Context) {
    fun schedule(notification: StoredNotification) {
        val nextAt = notification.plan.nextAtMs ?: return
        val triggerAt = if (nextAt <= System.currentTimeMillis()) System.currentTimeMillis() + 5_000 else nextAt
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        cancel(notification.id)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(notification.id, 1),
            fireIntent(notification.id),
            immutable(),
        )
        val exact = canScheduleExact(alarmManager)
        if (exact && notification.allowWhileIdle) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else if (exact) {
            alarmManager.setExact(AlarmManager.RTC, triggerAt, pending)
        } else if (notification.allowWhileIdle) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.set(AlarmManager.RTC, triggerAt, pending)
        }
    }

    fun cancel(id: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(id, 1),
            fireIntent(id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    fun canScheduleExact(): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return canScheduleExact(alarmManager)
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true
    }

    private fun fireIntent(id: Int): Intent {
        return Intent(context, AlarmReceiver::class.java)
            .setAction(Extras.ACTION_FIRE)
            .putExtra(Extras.ID, id)
    }
}

internal object NotificationRestorer {
    fun restore(context: Context) {
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager != null && !userManager.isUserUnlocked) return
        NotificationPresenter.ensureDefaultChannel(context)
        val store = NotificationStore(context)
        val scheduler = AlarmScheduler(context)
        for (notification in store.all()) {
            if (notification.plan.nextAtMs == null) continue
            scheduler.schedule(notification)
        }
    }

    fun advanceAfterDelivery(context: Context, notification: StoredNotification): StoredNotification? {
        val next = try {
            ScheduleCalculator.nextPlanAfter(notification.plan, System.currentTimeMillis(), TimeZone.getDefault())
        } catch (_: NotificationException) {
            null
        } ?: return null
        return notification.copy(plan = next)
    }
}
