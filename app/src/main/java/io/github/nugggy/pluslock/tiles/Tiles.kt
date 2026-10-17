package io.github.nugggy.pluslock.tiles

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.nugggy.pluslock.R
import io.github.nugggy.pluslock.core.Caffeine
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Pause
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.core.Silence
import io.github.nugggy.pluslock.core.SoundMode
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import io.github.nugggy.pluslock.ui.DeckActivity
import io.github.nugggy.pluslock.ui.MainActivity

abstract class BaseTile : TileService() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    override fun onStartListening() = render()

    abstract fun render()

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun openActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** Opens Pimp my Phone when the accessibility service is needed but off. */
    protected fun needService(): PlusAccessibilityService? {
        val svc = PlusAccessibilityService.instance
        if (svc == null) openActivity(Intent(this, MainActivity::class.java))
        return svc
    }

    protected fun set(state: Int, subtitle: String? = null, label: String? = null, icon: Int? = null) {
        val t = qsTile ?: return
        t.state = state
        if (subtitle != null) t.subtitle = subtitle
        if (label != null) t.label = label
        if (icon != null) t.icon = Icon.createWithResource(this, icon)
        t.updateTile()
    }
}

class CaffeineTile : BaseTile() {
    override fun onClick() {
        if (needService() == null) return
        Caffeine.cycle()
        render()
    }

    override fun render() {
        val until = P.caffeineUntil.value
        val now = System.currentTimeMillis()
        when {
            until == -1L -> set(Tile.STATE_ACTIVE, "Until off")
            until > now -> {
                val mins = ((until - now) / 60_000L) + 1
                set(Tile.STATE_ACTIVE, if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min")
            }
            else -> set(Tile.STATE_INACTIVE, "Off")
        }
    }
}

class LockTile : BaseTile() {
    override fun onClick() {
        needService()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    override fun render() = set(Tile.STATE_INACTIVE)
}

class ScreenshotTile : BaseTile() {
    override fun onClick() {
        val svc = needService() ?: return
        // Close the shade first so it isn't in the picture.
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        Handler(Looper.getMainLooper()).postDelayed({
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        }, 700)
    }

    override fun render() = set(Tile.STATE_INACTIVE)
}

class SoundModeTile : BaseTile() {
    override fun onClick() {
        SoundMode.cycle(this)
        render()
    }

    override fun render() {
        val mode = getSystemService(AudioManager::class.java).ringerMode
        val icon = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_tile_silent
            AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_tile_vibrate
            else -> R.drawable.ic_tile_sound
        }
        set(if (mode == AudioManager.RINGER_MODE_NORMAL) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, SoundMode.label(mode), icon = icon)
    }
}

class DeckTile : BaseTile() {
    override fun onClick() = openActivity(Intent(this, DeckActivity::class.java))
    override fun render() = set(Tile.STATE_INACTIVE)
}

class GesturesTile : BaseTile() {
    override fun onClick() {
        if (needService() == null) return
        P.gesturesOn.value = !P.gesturesOn.value
        render()
    }

    override fun render() =
        set(if (P.gesturesOn.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, if (P.gesturesOn.value) "On" else "Off")
}

class PauseLightingTile : BaseTile() {
    override fun onClick() {
        Silence.cycle()
        render()
    }

    override fun render() {
        val label = Silence.tileLabel()
        set(if (label != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, label ?: "Off")
    }
}

/** Stops every feature at once, to check whether the app is behind something the phone is doing. */
class PauseAllTile : BaseTile() {
    override fun onClick() {
        Pause.set(this, !Pause.on)
        render()
    }

    override fun render() {
        if (Pause.on) set(Tile.STATE_ACTIVE, "Everything paused") else set(Tile.STATE_INACTIVE, "Running")
    }
}
