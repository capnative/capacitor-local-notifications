package app.capnative.localnotifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.CapConfig
import java.util.UUID

internal object Extras {
    const val ID = "capnative_ln_id"
    const val ACTION = "capnative_ln_action"
    const val TOKEN = "capnative_ln_token"
    const val MARKER = "capnative_ln_marker"
    const val EXTRA = "capnative_ln_extra"
    const val TITLE = "capnative_ln_title"
    const val BODY = "capnative_ln_body"
    const val MARKER_VALUE = "capnative"
    const val DEFAULT_CHANNEL = "default"
    const val ACTION_FIRE = "app.capnative.localnotifications.FIRE"
    const val ACTION_DISMISS = "app.capnative.localnotifications.DISMISS"
}

internal object NotificationPresenter {
    /** Android 8+ refuses to post a notification unless one channel exists. */
    fun ensureDefaultChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(Extras.DEFAULT_CHANNEL) != null) return
        val config = pluginConfig(context)
        val channel = NotificationChannel(Extras.DEFAULT_CHANNEL, "Notifications", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Local notifications"
        val sound = config?.getString("sound")
        if (!sound.isNullOrEmpty()) {
            val soundId = resourceId(context, "raw", sound)
            if (soundId != 0) {
                channel.setSound(rawUri(context, soundId), audioAttributes())
            }
        }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, notification: StoredNotification) {
        ensureDefaultChannel(context)
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val config = pluginConfig(context)
        val builder = NotificationCompat.Builder(context, Extras.DEFAULT_CHANNEL)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setSmallIcon(smallIcon(context, notification.smallIcon, config?.getString("smallIcon")))
            .setAutoCancel(notification.autoCancel && !notification.ongoing)
            .setOngoing(notification.ongoing)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
        val color = notification.iconColor ?: config?.getString("iconColor")
        if (!color.isNullOrEmpty()) {
            try {
                builder.color = colorInt(color)
            } catch (_: IllegalArgumentException) {
                // A bad color in capacitor.config must not drop the notification.
            }
        }
        if (notification.largeIcon != null) {
            val iconId = resourceId(context, "drawable", notification.largeIcon)
            if (iconId != 0) {
                val bitmap = BitmapFactory.decodeResource(context.resources, iconId)
                if (bitmap != null) builder.setLargeIcon(bitmap)
            }
        }
        if (!notification.inbox.isNullOrEmpty()) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(notification.title)
            notification.inbox.forEach { style.addLine(it) }
            notification.summaryText?.let { style.setSummaryText(it) }
            builder.setStyle(style)
        } else if (notification.largeBody != null) {
            val style = NotificationCompat.BigTextStyle().bigText(notification.largeBody)
            notification.summaryText?.let { style.setSummaryText(it) }
            builder.setStyle(style)
        }
        if (notification.group != null) {
            builder.setGroup(notification.group)
            builder.setGroupSummary(notification.groupSummary)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            applyLegacySound(context, builder, notification.sound)
        }
        val extras = android.os.Bundle()
        extras.putString(Extras.MARKER, Extras.MARKER_VALUE)
        extras.putInt(Extras.ID, notification.id)
        if (notification.extraJson != null) extras.putString(Extras.EXTRA, notification.extraJson)
        builder.addExtras(extras)
        builder.setContentIntent(contentIntent(context, notification))
        builder.setDeleteIntent(dismissIntent(context, notification))
        manager.notify(notification.id, builder.build())
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    fun requireIcon(context: Context, name: String) {
        if (resourceId(context, "drawable", name) == 0) {
            throw NotificationException("INVALID_NOTIFICATION", "Drawable $name was not found.")
        }
    }

    fun requireLegacySound(context: Context, name: String) {
        if (name != "default" && name.isNotEmpty() && resourceId(context, "raw", name) == 0) {
            throw NotificationException("INVALID_NOTIFICATION", "Sound resource $name was not found in res/raw.")
        }
    }

    private fun smallIcon(context: Context, requested: String?, configured: String?): Int {
        val name = requested ?: configured
        if (name != null) {
            val id = resourceId(context, "drawable", name)
            if (id != 0) return id
        }
        return R.drawable.ic_stat_capnative_notification
    }

    private fun applyLegacySound(context: Context, builder: NotificationCompat.Builder, sound: String?) {
        when (sound) {
            null, "default" -> builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            "" -> builder.setSilent(true)
            else -> {
                val soundId = resourceId(context, "raw", sound)
                if (soundId != 0) builder.setSound(rawUri(context, soundId))
            }
        }
    }

    private fun contentIntent(context: Context, notification: StoredNotification): PendingIntent {
        val launch = launchIntent(context)
        putPayload(launch, notification, "tap")
        launch.putExtra(Extras.TOKEN, UUID.randomUUID().toString())
        return PendingIntent.getActivity(context, requestCode(notification.id, 2), launch, immutable())
    }

    private fun dismissIntent(context: Context, notification: StoredNotification): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(Extras.ACTION_DISMISS)
        putPayload(intent, notification, "dismiss")
        return PendingIntent.getBroadcast(context, requestCode(notification.id, 3), intent, immutable())
    }

    private fun putPayload(intent: Intent, notification: StoredNotification, actionId: String) {
        intent.putExtra(Extras.MARKER, Extras.MARKER_VALUE)
        intent.putExtra(Extras.ID, notification.id)
        intent.putExtra(Extras.ACTION, actionId)
        intent.putExtra(Extras.TITLE, notification.title)
        intent.putExtra(Extras.BODY, notification.body)
        if (notification.extraJson != null) intent.putExtra(Extras.EXTRA, notification.extraJson)
    }

    private fun pluginConfig(context: Context) = try {
        CapConfig.loadDefault(context).getPluginConfiguration("LocalNotifications")
    } catch (_: Exception) {
        null
    }

    private fun audioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
    }
}

internal fun launchIntent(context: Context): Intent {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
    launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    return launch
}

internal fun resourceId(context: Context, type: String, fileName: String): Int {
    val base = fileName.substringBeforeLast('.')
    if (!base.matches(Regex("^[A-Za-z0-9_]+$"))) return 0
    val direct = context.resources.getIdentifier(base, type, context.packageName)
    if (direct != 0) return direct
    return context.resources.getIdentifier(base.lowercase(), type, context.packageName)
}

internal fun rawUri(context: Context, soundId: Int): Uri {
    return Uri.parse("android.resource://${context.packageName}/$soundId")
}

internal fun colorInt(value: String): Int {
    val expanded = if (value.length == 4) {
        "#${value[1]}${value[1]}${value[2]}${value[2]}${value[3]}${value[3]}"
    } else {
        value
    }
    return Color.parseColor(expanded)
}

internal fun immutable(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

internal fun requestCode(id: Int, salt: Int): Int = id xor (salt * 0x45D9F3B)
