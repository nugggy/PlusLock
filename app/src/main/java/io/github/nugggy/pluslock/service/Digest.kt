package io.github.nugggy.pluslock.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import io.github.nugggy.pluslock.R
import io.github.nugggy.pluslock.core.Alarms
import io.github.nugggy.pluslock.core.Hours
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Status
import io.github.nugggy.pluslock.data.NotificationStore
import java.util.concurrent.Executors

/** A quiet morning summary of what came in overnight, built from the notification vault. */
class Digest(private val svc: PlusAccessibilityService) {
    private val io = Executors.newSingleThreadExecutor()

    fun schedule() {
        Alarms.cancel(svc, ALARM)
        if (!P.digestOn.value) return
        Alarms.set(svc, ALARM, System.currentTimeMillis() + Hours.millisUntilHour(P.digestHour.value, P.digestMinute.value))
    }

    fun onAlarm() {
        io.execute { post(svc) }
        schedule()
    }

    fun stop() {
        Alarms.cancel(svc, ALARM)
        io.shutdown()
    }

    companion object {
        const val ALARM = "digest"

        /** Builds and posts the digest. Returns false when there was nothing to report. */
        fun post(ctx: Context): Boolean {
            if (!Status.postNotifications(ctx)) return false
            val now = System.currentTimeMillis()
            // Since the last digest, but never more than a day back.
            val from = maxOf(P.digestLast.value, now - 24 * 3_600_000L)
            val items = NotificationStore.get(ctx).since(from)
            P.digestLast.value = now
            if (items.isEmpty()) return false
            val byApp = items.groupBy { it.app.ifBlank { it.pkg } }.toList().sortedByDescending { it.second.size }
            val lines = byApp.take(7).map { (app, list) ->
                val who = list.map { it.title }.filter { it.isNotBlank() && it != app }.distinct().take(3)
                "$app: ${list.size}" + if (who.isNotEmpty()) " (" + who.joinToString(", ") + ")" else ""
            }
            val summary = "${items.size} notification${if (items.size == 1) "" else "s"} from ${byApp.size} app${if (byApp.size == 1) "" else "s"}"
            val style = Notification.InboxStyle().setBigContentTitle("While you were away").setSummaryText(summary)
            lines.forEach { style.addLine(it) }
            val n = Notification.Builder(ctx, Notifs.CH_DIGEST)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("While you were away")
                .setContentText(summary)
                .setStyle(style)
                .setAutoCancel(true)
                .setContentIntent(Notifs.openApp(ctx, "vault"))
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(Notifs.ID_DIGEST, n)
            return true
        }
    }
}
