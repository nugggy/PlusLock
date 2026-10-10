package io.github.nugggy.pluslock.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.core.Torch

/**
 * The hub for everything that draws over other apps or reacts to the foreground app.
 * It only listens for window changes and never reads screen content.
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
    lateinit var keepAwake: KeepAwake
        private set
    lateinit var profiles: ProfileEngine
        private set
    lateinit var focus: FocusGuard
        private set
    private lateinit var battery: BatteryWatcher

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
        keepAwake = KeepAwake(this)
        focus = FocusGuard(this)
        profiles = ProfileEngine(this)
        battery = BatteryWatcher(this)
        imePackages = runCatching {
            getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        instance = this
        Prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        battery.start()
        gestures.refresh()
        ring.refresh()
        keepAwake.update()
    }

    private fun onPrefChanged(key: String) {
        when {
            key.startsWith("gesture") || key.startsWith("handle") -> gestures.refresh()
            key.startsWith("ring") -> ring.refresh()
            key == "caffeine_until" -> keepAwake.update()
            key == "profiles_json" || key == "profiles_on" -> profiles.reapply()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg in imePackages) return
        if (!isActivity(pkg, cls)) return
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
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
        }, 250)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        runCatching { Prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener) }
        if (::battery.isInitialized) battery.stop()
        if (::gestures.isInitialized) gestures.remove()
        if (::lighting.isInitialized) lighting.hide(immediate = true)
        if (::ring.isInitialized) ring.remove()
        if (::keepAwake.isInitialized) keepAwake.release()
        if (::focus.isInitialized) focus.dismiss()
        if (::profiles.isInitialized) profiles.restoreAll()
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
