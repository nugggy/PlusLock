package io.github.nugggy.pluslock.service

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Alarms
import io.github.nugggy.pluslock.core.Caffeine
import io.github.nugggy.pluslock.core.Hours
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Silence
import io.github.nugggy.pluslock.core.SoundMode
import io.github.nugggy.pluslock.core.Torch
import io.github.nugggy.pluslock.data.Routine
import io.github.nugggy.pluslock.data.RoutineAction
import io.github.nugggy.pluslock.data.RoutineActionType
import io.github.nugggy.pluslock.data.Routines
import io.github.nugggy.pluslock.data.Trigger
import io.github.nugggy.pluslock.data.TriggerType
import kotlin.math.roundToInt

/** Runs routines: "when this happens, do these things". */
class RoutineEngine(private val svc: PlusAccessibilityService) {
    private val am = svc.getSystemService(AudioManager::class.java)
    private val cm = svc.getSystemService(ConnectivityManager::class.java)
    private var started = false
    // 6: Wi-Fi is tracked as a set of networks, so roaming between access points isn't a disconnect.
    private val wifiNets = HashSet<Network>()
    private var wifiReady = false
    private var headphones: Boolean? = null
    private val armed = HashSet<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> svc.onClockChanged()
                Intent.ACTION_POWER_CONNECTED -> fire(TriggerType.CHARGER_IN)
                Intent.ACTION_POWER_DISCONNECTED -> fire(TriggerType.CHARGER_OUT)
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION")
                    val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = if (canBluetooth()) runCatching { dev?.name }.getOrNull().orEmpty() else ""
                    fire(if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) TriggerType.BT_ON else TriggerType.BT_OFF, name)
                }
            }
        }
    }

    private val wifi = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { svc.handler.post { setWifi(network, true) } }
        override fun onLost(network: Network) { svc.handler.post { setWifi(network, false) } }
    }

    private val audio = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkHeadphones()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkHeadphones()
    }

    fun start() {
        if (started) return
        started = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        svc.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        wifiNets.clear()
        wifiReady = false
        // The callback first reports networks that are already up; only later changes are triggers.
        svc.handler.postDelayed({ wifiReady = true }, 3_000)
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
        }
        headphones = headphonesNow()
        am.registerAudioDeviceCallback(audio, svc.handler)
        scheduleTimes()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { svc.unregisterReceiver(receiver) }
        runCatching { cm.unregisterNetworkCallback(wifi) }
        runCatching { am.unregisterAudioDeviceCallback(audio) }
        armed.forEach { Alarms.cancel(svc, it) }
        armed.clear()
    }

    /** Routines changed: re-arm the time-of-day ones. */
    fun reload() {
        if (started) scheduleTimes()
    }

    fun onAppOpened(pkg: String) = fire(TriggerType.APP_OPEN, pkg)

    private fun setWifi(n: Network, up: Boolean) {
        val was = wifiNets.isNotEmpty()
        if (up) wifiNets += n else wifiNets -= n
        val now = wifiNets.isNotEmpty()
        if (wifiReady && was != now) fire(if (now) TriggerType.WIFI_ON else TriggerType.WIFI_OFF)
    }

    private fun headphonesNow(): Boolean {
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    private fun checkHeadphones() {
        val now = headphonesNow()
        val was = headphones
        headphones = now
        if (was != null && was != now) fire(if (now) TriggerType.HEADPHONES_IN else TriggerType.HEADPHONES_OUT)
    }

    private fun canBluetooth() = svc.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Arms every time-of-day routine; cancels alarms for ones that were removed or switched off. */
    fun scheduleTimes() {
        val want = if (P.routinesOn.value) Routines.all().filter { it.on && it.trigger.type == TriggerType.TIME } else emptyList()
        val keys = want.map { KEY + it.id }.toSet()
        (armed - keys).forEach { Alarms.cancel(svc, it) }
        armed.clear()
        want.forEach { arm(it) }
    }

    private fun arm(r: Routine) {
        val (time, _) = Routines.timeArg(r.trigger.arg)
        val h = time.substringBefore(':').toIntOrNull() ?: return
        val m = time.substringAfter(':').toIntOrNull() ?: 0
        Alarms.set(svc, KEY + r.id, System.currentTimeMillis() + Hours.millisUntilHour(h, m))
        armed += KEY + r.id
    }

    /** A routine's alarm went off: run it if it's still wanted today, and set it for tomorrow. */
    fun onAlarm(key: String) {
        val r = Routines.get(key.removePrefix(KEY)) ?: return
        if (!P.routinesOn.value || !r.on || r.trigger.type != TriggerType.TIME) return
        if (!Routines.timeArg(r.trigger.arg).second || Hours.isWeekday()) run(r)
        arm(r)
    }

    companion object { const val KEY = "routine:" }

    private fun fire(type: TriggerType, detail: String = "") {
        if (!P.routinesOn.value) return
        Routines.all().filter { it.on && it.trigger.type == type && matches(it.trigger, detail) }.forEach { run(it) }
    }

    private fun matches(t: Trigger, detail: String): Boolean = when (t.type) {
        TriggerType.APP_OPEN -> t.arg == detail
        TriggerType.BT_ON, TriggerType.BT_OFF -> t.arg.isBlank() || t.arg.equals(detail, ignoreCase = true)
        else -> true
    }

    fun run(r: Routine) = r.actions.forEach { runCatching { act(it) } }

    private fun act(a: RoutineAction) {
        when (a.type) {
            RoutineActionType.OPEN_APP -> if (a.arg.isNotBlank()) Actions.run(svc, Actions.appAction(a.arg))
            RoutineActionType.MEDIA_VOLUME -> {
                val pct = a.arg.toIntOrNull() ?: 50
                val min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (min + (max - min) * pct / 100f).roundToInt(), 0)
            }
            RoutineActionType.SOUND_MODE -> SoundMode.set(svc, when (a.arg) {
                "RING" -> AudioManager.RINGER_MODE_NORMAL
                "SILENT" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_VIBRATE
            })
            RoutineActionType.DND -> {
                val nm = svc.getSystemService(NotificationManager::class.java)
                if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(
                    if (a.arg == "OFF") NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
            }
            RoutineActionType.TORCH -> Torch.setOn(svc, a.arg != "OFF")
            RoutineActionType.CAFFEINE -> Caffeine.set(if (a.arg == "OFF") 0L else -1L)
            RoutineActionType.PAUSE_LIGHTING -> {
                val m = a.arg.toIntOrNull() ?: 60
                if (m <= 0) Silence.resume() else Silence.forMinutes(m)
            }
            RoutineActionType.BRIGHTNESS -> if (Settings.System.canWrite(svc)) {
                val cr = svc.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, ((a.arg.toIntOrNull() ?: 50) * 255 / 100).coerceIn(1, 255))
            }
            RoutineActionType.GESTURE -> Actions.run(svc, a.arg.ifBlank { "PLAY_PAUSE" })
        }
    }
}
