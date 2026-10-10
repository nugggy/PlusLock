package io.github.nugggy.pluslock.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.StringPref
import io.github.nugggy.pluslock.data.AppProfile
import io.github.nugggy.pluslock.ui.LockActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fingerprint lock and daily time limits for apps.
 * Unlocked apps stay open until the screen goes off or you've been away for three minutes.
 */
class AppGuard(private val svc: PlusAccessibilityService) {
    companion object {
        @Volatile var instance: AppGuard? = null
            private set
        private val usageDay = StringPref("usage_day", "")
        private val usageJson = StringPref("usage_json", "{}")
        private const val RELOCK_AFTER = 3 * 60_000L
    }

    private val unlocked = HashMap<String, Long>() // pkg -> when it was last left, 0 while open
    private var pkg = ""
    private var profile: AppProfile? = null
    private var since = 0L
    private var counting = false
    private var overlay: View? = null
    private val limitCheck = Runnable { checkLimit() }

    init { instance = this }

    fun onForeground(newPkg: String, p: AppProfile?) {
        flush()
        if (pkg.isNotEmpty() && unlocked.containsKey(pkg)) unlocked[pkg] = SystemClock.elapsedRealtime()
        dismissOverlay()
        pkg = newPkg
        profile = p
        startCounting()
        if (p?.locked == true && !isUnlocked(newPkg)) launchLock(newPkg)
        else if (unlocked.containsKey(newPkg)) unlocked[newPkg] = 0L
    }

    /** Settings changed while this app is open. */
    fun reapply(p: AppProfile?) {
        profile = p
        svc.handler.removeCallbacks(limitCheck)
        if (counting) scheduleLimit()
    }

    private fun isUnlocked(p: String): Boolean {
        val left = unlocked[p] ?: return false
        return left == 0L || SystemClock.elapsedRealtime() - left < RELOCK_AFTER
    }

    fun unlock(p: String) { unlocked[p] = 0L }

    fun onScreenOff() {
        flush()
        counting = false
        svc.handler.removeCallbacks(limitCheck)
        unlocked.clear()
        dismissOverlay()
    }

    /** The phone was just unlocked: relock the app in front if it needs it, then start counting again. */
    fun onUnlocked() {
        val p = profile
        if (pkg.isNotEmpty() && p?.locked == true && !isUnlocked(pkg)) launchLock(pkg)
        startCounting()
    }

    private fun launchLock(p: String) {
        runCatching {
            svc.startActivity(
                Intent(svc, LockActivity::class.java)
                    .putExtra(LockActivity.EXTRA_PKG, p)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }

    // ---- daily limits ----

    private fun startCounting() {
        since = SystemClock.elapsedRealtime()
        counting = true
        svc.handler.removeCallbacks(limitCheck)
        scheduleLimit()
    }

    private fun scheduleLimit() {
        val limit = profile?.dailyMinutes ?: 0
        if (limit <= 0) return
        val remaining = limit * 60_000L + extraMs(pkg) - usedMs(pkg)
        svc.handler.postDelayed(limitCheck, remaining.coerceAtLeast(500L))
    }

    private fun checkLimit() {
        flush()
        val limit = profile?.dailyMinutes ?: return
        if (limit <= 0) return
        if (usedMs(pkg) >= limit * 60_000L + extraMs(pkg)) showLimit(limit) else scheduleLimit()
    }

    private fun flush() {
        if (!counting || pkg.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val add = now - since
        since = now
        if (add <= 0) return
        val o = today()
        o.put(pkg, o.optLong(pkg) + add)
        usageJson.value = o.toString()
    }

    private fun today(): JSONObject {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        if (usageDay.value != day) {
            usageDay.value = day
            usageJson.value = "{}"
        }
        return runCatching { JSONObject(usageJson.value) }.getOrDefault(JSONObject())
    }

    fun usedMs(p: String): Long = today().optLong(p)
    private fun extraMs(p: String): Long = today().optLong("+$p")

    private fun grantExtra(p: String, minutes: Int) {
        val o = today()
        o.put("+$p", o.optLong("+$p") + minutes * 60_000L)
        usageJson.value = o.toString()
    }

    private fun showLimit(limit: Int) {
        if (overlay != null) return
        val app = Apps.label(svc, pkg)
        val target = pkg
        val v = LimitView(svc, app, limit,
            onLeave = {
                dismissOverlay()
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            },
            onMore = {
                dismissOverlay()
                grantExtra(target, 15)
                scheduleLimit()
            })
        val b = svc.screenBounds()
        if (svc.addOverlay(v, svc.overlayParams(b.width(), b.height(), touchable = true))) overlay = v
    }

    fun dismissOverlay() {
        svc.removeOverlay(overlay)
        overlay = null
    }

    fun release() {
        flush()
        svc.handler.removeCallbacks(limitCheck)
        dismissOverlay()
        instance = null
    }
}

@SuppressLint("ViewConstructor", "SetTextI18n")
private class LimitView(ctx: Context, app: String, limit: Int, onLeave: () -> Unit, onMore: () -> Unit) : FrameLayout(ctx) {
    init {
        val d = resources.displayMetrics.density
        setBackgroundColor(0xF50A0C12.toInt())
        isClickable = true
        alpha = 0f
        animate().alpha(1f).setDuration(600).start()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0)
        }
        col.addView(TextView(ctx).apply {
            text = "That's today's $app time"
            setTextColor(Color.WHITE); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        col.addView(TextView(ctx).apply {
            text = "You gave yourself ${if (limit >= 60 && limit % 60 == 0) "${limit / 60} hour" + (if (limit > 60) "s" else "") else "$limit minutes"} a day. It resets at midnight."
            setTextColor(0xCCFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, (32 * d).toInt())
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun pill(label: String, filled: Boolean, tap: () -> Unit) = Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 15f
            setTextColor(if (filled) 0xFF0A0C12.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                if (filled) setColor(0xFF7FB2FF.toInt()) else { setColor(Color.TRANSPARENT); setStroke((1.5f * d).toInt(), 0x88FFFFFF.toInt()) }
            }
            setPadding((22 * d).toInt(), 0, (22 * d).toInt(), 0)
            setOnClickListener { tap() }
        }
        row.addView(pill("Close $app", true, onLeave), LinearLayout.LayoutParams(-2, (52 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
        row.addView(pill("15 more minutes", false, onMore), LinearLayout.LayoutParams(-2, (52 * d).toInt()))
        col.addView(row)
        addView(col, LayoutParams(-1, -2, Gravity.CENTER))
    }
}
