package io.github.nugggy.pluslock.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import io.github.nugggy.pluslock.ui.DeckActivity
import io.github.nugggy.pluslock.ui.MainActivity

enum class GestureAction(val label: String) {
    NONE("Do nothing"),
    BACK("Back"),
    HOME("Home"),
    RECENTS("Recent apps"),
    LAST_APP("Previous app"),
    NOTIFICATIONS("Notification panel"),
    QUICK_SETTINGS("Quick settings"),
    SCREENSHOT("Screenshot"),
    LOCK("Lock screen"),
    POWER("Power menu"),
    SPLIT("Split screen"),
    TORCH("Torch"),
    CONTROL_DECK("Control deck"),
    PLAY_PAUSE("Play or pause"),
    NEXT_TRACK("Next track"),
    PREV_TRACK("Previous track"),
    CAFFEINE("Caffeine on or off"),
    SOUND_MODE("Cycle sound mode"),
    VAULT("Notification vault"),
    ONE_HANDED("One-handed mode"),
    PAUSE_LIGHTING("Pause edge lighting"),
    SIDE_PANEL("Side panel"),
    EXTRA_DIM("Extra dim on or off"),
    LAUNCH_APP("Open an app"),
    VOLUME_PANEL("Volume panel"),
    VOLUME_BOOST("Volume boost on or off"),
    DND("Do Not Disturb on or off"),
    ROTATION("Auto-rotate on or off"),
    MUTE_MEDIA("Mute or unmute media"),
    QUICK_NOTE("Quick note"),
    CALCULATOR("Calculator in the side panel"),
}

object Actions {
    private const val APP_PREFIX = "APP:"
    private val main = Handler(Looper.getMainLooper())

    fun appAction(pkg: String) = APP_PREFIX + pkg

    fun label(ctx: Context, value: String): String =
        if (value.startsWith(APP_PREFIX)) "Open " + Apps.label(ctx, value.removePrefix(APP_PREFIX))
        else runCatching { GestureAction.valueOf(value).label }.getOrDefault("Do nothing")

    fun run(ctx: Context, value: String) {
        if (value.startsWith(APP_PREFIX)) {
            val pkg = value.removePrefix(APP_PREFIX)
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(it) }
            }
            return
        }
        val action = runCatching { GestureAction.valueOf(value) }.getOrDefault(GestureAction.NONE)
        val svc = PlusAccessibilityService.instance
        fun global(a: Int) {
            if (svc == null) toast(ctx, "Turn on the Pimp my Phone accessibility service first")
            else svc.performGlobalAction(a)
        }
        when (action) {
            GestureAction.NONE, GestureAction.LAUNCH_APP -> Unit
            GestureAction.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            GestureAction.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            GestureAction.LAST_APP -> {
                // Double-tapping recents jumps to the previous app on most launchers.
                global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                main.postDelayed({ global(AccessibilityService.GLOBAL_ACTION_RECENTS) }, 180)
            }
            GestureAction.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.QUICK_SETTINGS -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            GestureAction.SCREENSHOT -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            GestureAction.LOCK -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            GestureAction.POWER -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            GestureAction.SPLIT -> global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            GestureAction.TORCH -> Torch.toggle(ctx)
            GestureAction.CONTROL_DECK -> ctx.startActivity(
                Intent(ctx, DeckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            GestureAction.PLAY_PAUSE -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            GestureAction.NEXT_TRACK -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
            GestureAction.PREV_TRACK -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            GestureAction.CAFFEINE -> {
                val on = Caffeine.isActive()
                Caffeine.set(if (on) 0L else -1L)
                toast(ctx, if (on) "Caffeine off" else "Caffeine on until you turn it off")
            }
            GestureAction.SOUND_MODE -> toast(ctx, SoundMode.cycle(ctx))
            GestureAction.ONE_HANDED -> if (!SystemSettings.oneHanded(ctx)) {
                toast(ctx, if (SystemSettings.hasSecure(ctx)) "One-handed mode isn't available on this phone"
                else "One-handed mode needs the one-off ADB step in Pimp my Phone")
            }
            GestureAction.SIDE_PANEL -> if (svc == null) toast(ctx, "Turn on the Pimp my Phone accessibility service first") else svc.panel.toggle()
            GestureAction.EXTRA_DIM -> {
                P.dimOn.value = !P.dimOn.value
                toast(ctx, if (P.dimOn.value) "Extra dim on" else "Extra dim off")
            }
            GestureAction.PAUSE_LIGHTING -> toast(ctx, "Edge lighting: " + Silence.cycle())
            GestureAction.VOLUME_PANEL -> if (svc == null) toast(ctx, "Turn on the Pimp my Phone accessibility service first") else svc.volumePanel.preview()
            GestureAction.VOLUME_BOOST -> {
                P.boostOn.value = !P.boostOn.value
                toast(ctx, if (P.boostOn.value) "Volume boost on" else "Volume boost off")
            }
            GestureAction.DND -> {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) toast(ctx, "Allow Do Not Disturb access for Pimp my Phone first")
                else {
                    val on = nm.currentInterruptionFilter > android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_ALL else android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    toast(ctx, if (on) "Do Not Disturb off" else "Do Not Disturb on")
                }
            }
            GestureAction.ROTATION -> {
                if (!android.provider.Settings.System.canWrite(ctx)) toast(ctx, "Allow Modify system settings for Pimp my Phone first")
                else {
                    val cr = ctx.contentResolver
                    val on = android.provider.Settings.System.getInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                    android.provider.Settings.System.putInt(cr, android.provider.Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
                    toast(ctx, if (on) "Auto-rotate off" else "Auto-rotate on")
                }
            }
            GestureAction.MUTE_MEDIA -> ctx.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            GestureAction.QUICK_NOTE -> ctx.startActivity(
                Intent(ctx, io.github.nugggy.pluslock.ui.NoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            GestureAction.CALCULATOR -> if (svc == null) toast(ctx, "Turn on the Pimp my Phone accessibility service first") else svc.panel.show(calculator = true)
            GestureAction.VAULT -> ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SCREEN, "vault")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}

object Caffeine {
    fun isActive(): Boolean {
        val until = P.caffeineUntil.value
        return until == -1L || until > System.currentTimeMillis()
    }

    fun set(until: Long) {
        P.caffeineUntil.value = until
    }

    /** Off, 15 min, 1 h, indefinite, then off again. Returns a label for the new state. */
    fun cycle(): String {
        val until = P.caffeineUntil.value
        val now = System.currentTimeMillis()
        val remaining = if (until > now) until - now else 0L
        return when {
            until == -1L -> { set(0L); "Off" }
            remaining > 16 * 60_000L -> { set(-1L); "Until off" }
            remaining > 0L -> { set(now + 60 * 60_000L); "1 hour" }
            else -> { set(now + 15 * 60_000L); "15 minutes" }
        }
    }
}

object SoundMode {
    fun label(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else -> "Ring"
    }

    fun set(ctx: Context, mode: Int): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        return runCatching { am.ringerMode = mode; true }.getOrDefault(false)
    }

    fun cycle(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        return if (set(ctx, next)) label(next)
        else "Pimp my Phone needs notification access to change sound mode"
    }
}
