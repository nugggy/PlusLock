package io.github.nugggy.pluslock.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.core.Silence
import io.github.nugggy.pluslock.core.Torch
import io.github.nugggy.pluslock.core.VolumeBoost

/**
 * The hub for everything that draws over other apps or reacts to the foreground app.
 * It only listens for window changes and never reads screen content. Key events are
 * only requested while volume-button track skipping is on, and only volume keys are used.
 */
class PlusAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PlusAccessibilityService? = null
            private set
    }

    lateinit var wm: WindowManager
        private set
    val handler = Handler(Looper.getMainLooper())

    lateinit var gestures: EdgeGestures
        private set
    lateinit var lighting: EdgeLighting
        private set
    lateinit var ring: CameraRing
        private set
    lateinit var chargeFx: ChargingFx
        private set
    lateinit var keepAwake: KeepAwake
        private set
    lateinit var profiles: ProfileEngine
        private set
    lateinit var focus: FocusGuard
        private set
    lateinit var guard: AppGuard
        private set
    private lateinit var battery: BatteryWatcher
    private lateinit var motion: MotionGestures
    private lateinit var statusTap: StatusBarTap
    private lateinit var netSpeed: NetSpeed
    private lateinit var lockClock: LockClock
    private lateinit var volumeSkip: VolumeSkip
    lateinit var volumePanel: VolumePanel
        private set
    lateinit var panel: SidePanel
        private set
    private lateinit var island: Island
    private lateinit var filter: ScreenFilter
    private lateinit var digest: Digest
    lateinit var routines: RoutineEngine
        private set

    /** Set by app profiles that turn gestures off, so games keep the top of the screen. */
    var statusTapSuppressed = false
        set(v) { if (field != v) { field = v; if (::statusTap.isInitialized) statusTap.refresh() } }

    @Volatile var foregroundPkg: String = ""
        private set

    private val activityCache = HashMap<String, Boolean>()
    private var imePackages: Set<String> = emptySet()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        handler.post { onPrefChanged(key ?: return@post) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        Torch.init(this)
        wm = getSystemService(WindowManager::class.java)
        gestures = EdgeGestures(this)
        lighting = EdgeLighting(this)
        ring = CameraRing(this)
        chargeFx = ChargingFx(this)
        keepAwake = KeepAwake(this)
        focus = FocusGuard(this)
        guard = AppGuard(this)
        profiles = ProfileEngine(this)
        battery = BatteryWatcher(this)
        motion = MotionGestures(this)
        statusTap = StatusBarTap(this)
        netSpeed = NetSpeed(this)
        lockClock = LockClock(this)
        volumePanel = VolumePanel(this)
        volumeSkip = VolumeSkip(this, volumePanel)
        panel = SidePanel(this)
        island = Island(this)
        filter = ScreenFilter(this)
        digest = Digest(this)
        routines = RoutineEngine(this)
        imePackages = runCatching {
            getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        instance = this
        Prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        // Put back anything a previous run changed and didn't get to restore.
        profiles.restoreAll()
        battery.start()
        gestures.refresh()
        ring.refresh()
        keepAwake.update()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, RECEIVER_EXPORTED)
        motion.refresh()
        statusTap.refresh()
        netSpeed.refresh()
        lockClock.refresh()
        updateKeyFilter()
        island.refresh()
        filter.refresh()
        digest.schedule()
        routines.start()
        VolumeBoost.init(this)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    motion.screenOff(); netSpeed.remove(); guard.onScreenOff(); statusTap.resume()
                    panel.hide(); volumePanel.hide(); island.remove()
                    lighting.hide(immediate = true)
                    chargeFx.onScreenOff()
                    if (P.ringWhere.value != "ALL") ring.refresh()
                }
                Intent.ACTION_SCREEN_ON -> {
                    motion.refresh(); netSpeed.refresh(); island.refresh()
                    chargeFx.onScreenOn()
                    if (P.ringWhere.value != "ALL") ring.refresh()
                }
                Intent.ACTION_USER_PRESENT -> {
                    guard.onUnlocked()
                    chargeFx.onUnlocked()
                    if (P.ringWhere.value != "ALL") ring.refresh()
                }
            }
            lockClock.refresh()
        }
    }

    /** Volume-button track skipping needs key events, which are only requested while it's switched on. */
    private fun updateKeyFilter() {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val want = volumeSkip.wanted
        val has = info.flags and flag != 0
        if (want == has) return
        info.flags = if (want) info.flags or flag else info.flags and flag.inv()
        serviceInfo = info
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = volumeSkip.onKey(event)

    /** An exact alarm from [io.github.nugggy.pluslock.core.Alarms] went off. */
    fun onAlarm(key: String) {
        when {
            key == Digest.ALARM -> digest.onAlarm()
            key == BatteryWatcher.ALARM -> battery.checkBedtime()
            key.startsWith(RoutineEngine.KEY) -> routines.onAlarm(key)
        }
    }

    /** The clock or time zone changed, so every scheduled time needs working out again. */
    fun onClockChanged() {
        routines.scheduleTimes()
        digest.schedule()
        battery.scheduleBedtime()
    }

    private fun onPrefChanged(key: String) {
        when {
            key.startsWith("gesture") || key.startsWith("handle") -> gestures.refresh()
            key.startsWith("ring") -> ring.refresh()
            key.startsWith("chargefx") || key == "charging_glow" || key == "lighting_landscape_off" -> chargeFx.refresh()
            key == "caffeine_until" -> keepAwake.update()
            key == "profiles_json" || key == "profiles_on" -> profiles.reapply()
            key.startsWith("motion") -> motion.refresh()
            key.startsWith("statustap") -> statusTap.refresh()
            key.startsWith("netspeed") -> netSpeed.refresh()
            key.startsWith("lockclock") -> lockClock.refresh()
            key.startsWith("bedtime") -> battery.scheduleBedtime()
            key == "volskip_on" || key == "volpanel_on" -> updateKeyFilter()
            key.startsWith("island") -> island.refresh()
            key.startsWith("dim") -> filter.refresh()
            key.startsWith("digest") && key != "digest_last" -> digest.schedule()
            key.startsWith("routines") -> routines.reload()
            key.startsWith("boost") -> VolumeBoost.apply()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == "com.android.systemui") {
            // The lock screen is SystemUI: show the custom clock again when it's back in front.
            lockClock.refresh()
            return
        }
        if (pkg == packageName || pkg in imePackages) return
        // Another window over the lock screen (the camera, say) shouldn't wear the clock.
        lockClock.hideIfCovered()
        if (!isActivity(pkg, cls)) return
        statusTap.resume()
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        Silence.onForeground(pkg)
        routines.onAppOpened(pkg)
        profiles.onForeground(pkg)
    }

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut("$pkg/$cls") {
        runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), PackageManager.MATCH_ALL); true }
            .getOrDefault(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({
            gestures.refresh()
            ring.refresh()
            statusTap.refresh()
            netSpeed.refresh()
            lockClock.refresh()
            island.refresh()
            filter.refresh()
            chargeFx.refresh()
        }, 250)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        runCatching { Prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener) }
        runCatching { unregisterReceiver(screenReceiver) }
        if (::battery.isInitialized) battery.stop()
        if (::motion.isInitialized) { motion.restoreFlip(); motion.stop() }
        if (::statusTap.isInitialized) statusTap.remove()
        if (::netSpeed.isInitialized) netSpeed.remove()
        if (::lockClock.isInitialized) lockClock.remove()
        if (::guard.isInitialized) guard.release()
        if (::panel.isInitialized) panel.hide()
        if (::volumePanel.isInitialized) volumePanel.hide()
        if (::island.isInitialized) island.release()
        if (::filter.isInitialized) filter.remove()
        if (::digest.isInitialized) digest.stop()
        if (::routines.isInitialized) routines.stop()
        if (::gestures.isInitialized) gestures.remove()
        if (::lighting.isInitialized) lighting.hide(immediate = true)
        if (::ring.isInitialized) ring.remove()
        if (::chargeFx.isInitialized) chargeFx.release()
        if (::keepAwake.isInitialized) keepAwake.release()
        if (::focus.isInitialized) focus.dismiss()
        if (::profiles.isInitialized) profiles.restoreAll()
        VolumeBoost.releaseAll()
        super.onDestroy()
    }

    // ---- overlay helpers shared by the components ----

    val density: Float get() = resources.displayMetrics.density
    fun dp(v: Float) = (v * density)
    fun screenBounds(): Rect = wm.currentWindowMetrics.bounds
    val isLandscape: Boolean get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
    }

    fun addOverlay(v: View, lp: WindowManager.LayoutParams): Boolean =
        runCatching { wm.addView(v, lp); true }.getOrDefault(false)

    fun removeOverlay(v: View?) {
        if (v == null) return
        runCatching { wm.removeViewImmediate(v) }
    }
}
