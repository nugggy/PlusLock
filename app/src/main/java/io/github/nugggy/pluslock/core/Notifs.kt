package io.github.nugggy.pluslock.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.nugggy.pluslock.R
import io.github.nugggy.pluslock.ui.MainActivity

object Notifs {
    const val CH_UPDATES = "updates"
    const val CH_ALERTS = "alerts"

    const val ID_UPDATE = 100
    const val ID_CHARGE = 200
    const val ID_UNSENT_BASE = 1000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "New PlusLock versions and install results"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Charge guard and unsent message alerts"
            }
        )
    }

    fun openApp(ctx: Context, screen: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (screen != null) i.putExtra(MainActivity.EXTRA_SCREEN, screen)
        return PendingIntent.getActivity(ctx, screen.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(ctx: Context, id: Int, channel: String, title: String, text: String, tap: PendingIntent? = null) {
        if (!Status.postNotifications(ctx)) return
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap ?: openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
