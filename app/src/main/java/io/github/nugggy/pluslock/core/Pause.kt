package io.github.nugggy.pluslock.core

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.quicksettings.TileService
import io.github.nugggy.pluslock.service.PlusNotificationListener
import io.github.nugggy.pluslock.tiles.PauseAllTile

/**
 * One switch that stops everything, so you can tell whether the app is behind something
 * odd the phone is doing. Your settings are kept; switching back on picks up where it was.
 *
 * While paused the accessibility service tears down every overlay, sensor, receiver and
 * schedule and puts back any system settings app profiles changed, the notification
 * listener disconnects, volume effects come off and scheduled update checks are skipped.
 */
object Pause {
    val on: Boolean get() = P.allPaused.value

    fun set(ctx: Context, paused: Boolean) {
        if (P.allPaused.value == paused) return
        // The accessibility service follows this through its settings listener.
        P.allPaused.value = paused
        Diag.log("pause", if (paused) "everything paused" else "everything running again")
        VolumeBoost.apply()
        AppAudio.reapply()
        if (paused) {
            PlusNotificationListener.instance?.let { runCatching { it.requestUnbind() } }
        } else {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(ctx, PlusNotificationListener::class.java))
            }
        }
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, PauseAllTile::class.java)) }
    }
}
