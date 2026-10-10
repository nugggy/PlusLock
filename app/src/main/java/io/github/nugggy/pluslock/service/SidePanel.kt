package io.github.nugggy.pluslock.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.Caffeine
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Silence
import io.github.nugggy.pluslock.core.SoundMode
import io.github.nugggy.pluslock.core.Torch
import io.github.nugggy.pluslock.ui.MainActivity
import org.json.JSONArray

data class PanelContact(val name: String, val number: String)

object PanelContacts {
    fun all(): List<PanelContact> {
        val a = runCatching { JSONArray(P.panelContacts.value) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map { PanelContact(it.optString("name"), it.optString("number")) }
    }

    fun save(list: List<PanelContact>) {
        P.panelContacts.value = JSONArray().apply {
            list.forEach { put(org.json.JSONObject().put("name", it.name).put("number", it.number)) }
        }.toString()
    }
}

/** Edge panel: favourite apps, people and quick toggles, slid in from the side of the screen. */
class SidePanel(private val svc: PlusAccessibilityService) {
    private var view: View? = null

    val isShowing get() = view != null

    fun toggle() = if (isShowing) hide() else show()

    fun show() {
        hide()
        val b = svc.screenBounds()
        val right = P.panelSide.value != "LEFT"
        val insets = svc.wm.currentWindowMetrics.windowInsets
        val top = insets.getInsets(WindowInsets.Type.statusBars()).top
        val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        val v = PanelView(svc, right, top, bottom, ::hide)
        if (svc.addOverlay(v, svc.overlayParams(b.width(), b.height(), touchable = true))) view = v
    }

    fun hide() {
        val v = view ?: return
        view = null
        svc.removeOverlay(v)
    }
}

@SuppressLint("ViewConstructor", "SetTextI18n")
private class PanelView(
    ctx: Context,
    right: Boolean,
    topInset: Int,
    bottomInset: Int,
    private val close: () -> Unit,
) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val svc get() = PlusAccessibilityService.instance

    init {
        setBackgroundColor(0x66000000)
        setOnClickListener { close() }
        val w = (resources.displayMetrics.widthPixels * 0.78f).toInt().coerceAtMost((340 * d).toInt())
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xF5121418.toInt())
                val r = 28 * d
                cornerRadii = if (right) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            isClickable = true // taps on the panel shouldn't close it
        }
        card.addView(heading("Quick toggles"))
        card.addView(toggles())
        val apps = P.panelApps.value.split(',').filter { it.isNotBlank() }
        card.addView(heading("Apps"))
        if (apps.isEmpty()) card.addView(hint("Add favourite apps in the side panel settings."))
        else card.addView(appGrid(apps))
        val people = PanelContacts.all()
        card.addView(heading("People"))
        if (people.isEmpty()) card.addView(hint("Add people in the side panel settings. Tap to call, hold to message."))
        else people.forEach { card.addView(person(it)) }
        card.addView(TextView(ctx).apply {
            text = "Edit panel"
            setTextColor(0xFF7FB2FF.toInt()); textSize = 14f
            setPadding(0, (14 * d).toInt(), 0, 0)
            setOnClickListener {
                close()
                ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "panel")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        val scroll = ScrollView(ctx).apply { addView(card); isVerticalScrollBarEnabled = false }
        addView(scroll, LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL).apply {
            topMargin = topInset + (12 * d).toInt(); bottomMargin = bottomInset + (12 * d).toInt()
        })
        scroll.translationX = if (right) w.toFloat() else -w.toFloat()
        scroll.animate().translationX(0f).setDuration(220).start()
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
    }

    private fun heading(t: String) = TextView(context).apply {
        text = t; setTextColor(0xFF7FB2FF.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (10 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun hint(t: String) = TextView(context).apply { text = t; setTextColor(0x99FFFFFF.toInt()); textSize = 13f }

    private fun toggles(): View {
        val grid = GridLayout(context).apply { columnCount = 3 }
        fun add(label: String, active: Boolean, tap: () -> Unit) {
            grid.addView(TextView(context).apply {
                text = label; gravity = Gravity.CENTER; textSize = 12.5f
                setTextColor(if (active) 0xFF0A0C12.toInt() else Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(if (active) 0xFF7FB2FF.toInt() else 0x22FFFFFF) }
                setPadding((6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt())
                setOnClickListener { tap() }
            }, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
            })
        }
        val ctx = context
        add("Torch", Torch.isOn) { Torch.toggle(ctx); close() }
        add("Caffeine", Caffeine.isActive()) { Caffeine.set(if (Caffeine.isActive()) 0L else -1L); close() }
        add(SoundMode.label(ctx.getSystemService(android.media.AudioManager::class.java).ringerMode), false) { Actions.toast(ctx, SoundMode.cycle(ctx)); close() }
        add(if (Silence.isActive(svc?.foregroundPkg.orEmpty())) "Lighting paused" else "Pause lighting", Silence.isActive(svc?.foregroundPkg.orEmpty())) {
            if (Silence.isActive(svc?.foregroundPkg.orEmpty())) Silence.resume() else Silence.forMinutes(60)
            close()
        }
        add("Screenshot", false) {
            close()
            svc?.handler?.postDelayed({ svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 350)
        }
        add("Lock", false) { close(); svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
        return grid
    }

    private fun appGrid(apps: List<String>): View {
        val grid = GridLayout(context).apply { columnCount = 4 }
        apps.forEach { pkg ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                setOnClickListener { close(); Actions.run(context, Actions.appAction(pkg)) }
            }
            cell.addView(ImageView(context).apply { setImageDrawable(Apps.icon(context, pkg)) }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
            cell.addView(TextView(context).apply {
                text = Apps.label(context, pkg); setTextColor(Color.WHITE); textSize = 11f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding(0, (4 * d).toInt(), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            grid.addView(cell, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
        }
        return grid
    }

    private fun person(c: PanelContact) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        addView(TextView(context).apply {
            text = c.name.take(1).uppercase(); gravity = Gravity.CENTER; setTextColor(0xFF0A0C12.toInt())
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF7FB2FF.toInt()) }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()))
        addView(TextView(context).apply {
            text = c.name; setTextColor(Color.WHITE); textSize = 15f
            setPadding((12 * d).toInt(), 0, 0, 0)
        })
        setOnClickListener { close(); open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.number))) }
        setOnLongClickListener { close(); open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + c.number))); true }
    }

    private fun open(i: Intent) {
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
