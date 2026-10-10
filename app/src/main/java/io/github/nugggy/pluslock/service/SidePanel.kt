package io.github.nugggy.pluslock.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
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
import io.github.nugggy.pluslock.data.NotificationStore
import io.github.nugggy.pluslock.ui.MainActivity
import io.github.nugggy.pluslock.ui.NoteActivity
import org.json.JSONArray
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

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

/** Everything the side panel can show, in the order the settings list them. */
val panelSections = linkedMapOf(
    "TOGGLES" to "Quick toggles",
    "APPS" to "Favourite apps",
    "PEOPLE" to "People",
    "NOTE" to "Quick note",
    "CALC" to "Calculator",
    "RECENT" to "Recent notifications",
)

val panelToggles = linkedMapOf(
    "TORCH" to "Torch",
    "CAFFEINE" to "Caffeine",
    "SOUND" to "Sound mode",
    "PAUSE" to "Pause lighting",
    "SCREENSHOT" to "Screenshot",
    "LOCK" to "Lock",
    "DND" to "Do Not Disturb",
    "ROTATION" to "Auto-rotate",
    "BOOST" to "Volume boost",
    "DIM" to "Extra dim",
    "MUTE" to "Mute media",
    "VOLUME" to "Volume panel",
)

/** Edge panel: toggles, favourite apps, people, a note, a calculator and recent notifications. */
class SidePanel(private val svc: PlusAccessibilityService) {
    private var view: View? = null

    val isShowing get() = view != null

    fun toggle() = if (isShowing) hide() else show()

    fun show(calculator: Boolean = false) {
        hide()
        val b = svc.screenBounds()
        val right = P.panelSide.value != "LEFT"
        val insets = svc.wm.currentWindowMetrics.windowInsets
        val top = insets.getInsets(WindowInsets.Type.statusBars()).top
        val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        val v = PanelView(svc, right, top, bottom, calculator, ::hide)
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
    calculatorOnly: Boolean,
    private val close: () -> Unit,
) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val svc get() = PlusAccessibilityService.instance
    private val accent = P.panelColour.value
    private val cardColour = when (P.panelStyle.value) { "BLACK" -> 0xFF000000.toInt(); "GLASS" -> 0xB0181A20.toInt(); else -> 0xF5121418.toInt() }

    init {
        setBackgroundColor(0x66000000)
        setOnClickListener { close() }
        val w = (resources.displayMetrics.widthPixels * P.panelWidth.value / 100f).toInt().coerceAtMost((380 * d).toInt())
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable().apply {
                setColor(cardColour)
                if (P.panelStyle.value != "DARK") setStroke((1 * d).toInt(), 0x22FFFFFF)
                val r = 28 * d
                cornerRadii = if (right) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            isClickable = true // taps on the panel shouldn't close it
        }
        val sections = if (calculatorOnly) listOf("CALC") else P.panelSections.value.split(',').filter { it in panelSections }
        sections.forEach { s ->
            when (s) {
                "TOGGLES" -> { card.addView(heading("Quick toggles")); card.addView(toggles()) }
                "APPS" -> {
                    val apps = P.panelApps.value.split(',').filter { it.isNotBlank() }
                    card.addView(heading("Apps"))
                    if (apps.isEmpty()) card.addView(hint("Add favourite apps in the side panel settings.")) else card.addView(appGrid(apps))
                }
                "PEOPLE" -> {
                    val people = PanelContacts.all()
                    card.addView(heading("People"))
                    if (people.isEmpty()) card.addView(hint("Add people in the side panel settings. Tap to call, hold to message."))
                    else people.forEach { card.addView(person(it)) }
                }
                "NOTE" -> { card.addView(heading("Quick note")); card.addView(note()) }
                "CALC" -> { card.addView(heading("Calculator")); card.addView(Calculator(ctx, accent)) }
                "RECENT" -> { card.addView(heading("Recent notifications")); card.addView(recent()) }
            }
        }
        card.addView(TextView(ctx).apply {
            text = "Edit panel"
            setTextColor(accent); textSize = 14f
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
        text = t; setTextColor(accent); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (10 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun hint(t: String) = TextView(context).apply { text = t; setTextColor(0x99FFFFFF.toInt()); textSize = 13f }

    private fun chip(label: String, active: Boolean, tap: () -> Unit) = TextView(context).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12.5f
        maxLines = 2
        setTextColor(if (active) 0xFF0A0C12.toInt() else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(if (active) accent else 0x22FFFFFF) }
        setPadding((6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt())
        setOnClickListener { tap() }
    }

    private fun gridParams() = GridLayout.LayoutParams().apply {
        width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
    }

    private fun toggles(): View {
        val grid = GridLayout(context).apply { columnCount = 3 }
        val ctx = context
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val paused = Silence.isActive(svc?.foregroundPkg.orEmpty())
        P.panelToggles.value.split(',').filter { it in panelToggles }.forEach { id ->
            val v = when (id) {
                "TORCH" -> chip("Torch", Torch.isOn) { Torch.toggle(ctx); close() }
                "CAFFEINE" -> chip("Caffeine", Caffeine.isActive()) { Caffeine.set(if (Caffeine.isActive()) 0L else -1L); close() }
                "SOUND" -> chip(SoundMode.label(am.ringerMode), false) { Actions.toast(ctx, SoundMode.cycle(ctx)); close() }
                "PAUSE" -> chip(if (paused) "Lighting paused" else "Pause lighting", paused) {
                    if (paused) Silence.resume() else Silence.forMinutes(60); close()
                }
                "SCREENSHOT" -> chip("Screenshot", false) {
                    close()
                    svc?.handler?.postDelayed({ svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 350)
                }
                "LOCK" -> chip("Lock", false) { close(); svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
                "DND" -> chip("Do Not Disturb", nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) { Actions.run(ctx, "DND"); close() }
                "ROTATION" -> chip("Auto-rotate", Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1) {
                    Actions.run(ctx, "ROTATION"); close()
                }
                "BOOST" -> chip("Volume boost", P.boostOn.value) { Actions.run(ctx, "VOLUME_BOOST"); close() }
                "DIM" -> chip("Extra dim", P.dimOn.value) { Actions.run(ctx, "EXTRA_DIM"); close() }
                "MUTE" -> chip("Mute media", am.isStreamMute(AudioManager.STREAM_MUSIC)) { Actions.run(ctx, "MUTE_MEDIA"); close() }
                "VOLUME" -> chip("Volume", false) { close(); svc?.handler?.postDelayed({ svc?.volumePanel?.preview() }, 250) }
                else -> null
            }
            if (v != null) grid.addView(v, gridParams())
        }
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
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
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
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        }, LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()))
        addView(TextView(context).apply {
            text = c.name; setTextColor(Color.WHITE); textSize = 15f
            setPadding((12 * d).toInt(), 0, 0, 0)
        })
        setOnClickListener { close(); open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.number))) }
        setOnLongClickListener { close(); open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + c.number))); true }
    }

    /** The note itself, tap to edit it in a small window with the keyboard. */
    private fun note() = TextView(context).apply {
        val n = P.panelNote.value
        text = n.ifBlank { "Tap to write something down" }
        setTextColor(if (n.isBlank()) 0x99FFFFFF.toInt() else Color.WHITE); textSize = 14f
        maxLines = 6; ellipsize = TextUtils.TruncateAt.END
        background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(0x14FFFFFF) }
        setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        setOnClickListener { close(); open(Intent(context, NoteActivity::class.java)) }
    }

    private fun recent(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val list = runCatching { NotificationStore.get(context).query(null, null, false, 5) }.getOrDefault(emptyList())
        if (list.isEmpty()) box.addView(hint(if (P.vaultOn.value) "Nothing yet." else "Switch on the notification vault to see these."))
        list.forEach { n ->
            box.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                addView(ImageView(context).apply { setImageDrawable(Apps.icon(context, n.pkg)) }, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()))
                addView(TextView(context).apply {
                    text = n.title.ifBlank { n.app } + ": " + n.body
                    setTextColor(Color.WHITE); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setPadding((10 * d).toInt(), 0, 0, 0)
                })
                setOnClickListener {
                    close()
                    context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "vault")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
        return box
    }

    private fun open(i: Intent) {
        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** A small calculator built from buttons, so it works in the panel without a keyboard. */
@SuppressLint("ViewConstructor", "SetTextI18n")
private class Calculator(ctx: Context, private val accent: Int) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private var expr = ""
    private val display = TextView(ctx).apply {
        textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.END
        maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
    }
    private val preview = TextView(ctx).apply {
        textSize = 14f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.END
        setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (8 * d).toInt())
        setOnClickListener {
            // Tap the answer to copy it.
            val v = text.toString().removePrefix("= ")
            if (v.isNotBlank()) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Answer", v))
                Actions.toast(context, "Copied $v")
            }
        }
    }

    init {
        orientation = VERTICAL
        addView(display, LayoutParams(-1, -2))
        addView(preview, LayoutParams(-1, -2))
        val keys = listOf("C", "⌫", "%", "÷", "7", "8", "9", "×", "4", "5", "6", "−", "1", "2", "3", "+", "±", "0", ".", "=")
        val grid = GridLayout(ctx).apply { columnCount = 4 }
        keys.forEach { k ->
            val op = k in listOf("÷", "×", "−", "+", "=")
            grid.addView(TextView(ctx).apply {
                text = k; gravity = Gravity.CENTER; textSize = 18f
                setTextColor(if (k == "=") 0xFF0A0C12.toInt() else if (op) accent else Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(if (k == "=") accent else 0x1AFFFFFF) }
                setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
                setOnClickListener { press(k) }
            }, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
            })
        }
        addView(grid)
        show()
    }

    private fun press(k: String) {
        when (k) {
            "C" -> expr = ""
            "⌫" -> expr = expr.dropLast(1)
            "=" -> { eval(expr)?.let { expr = it } }
            "±" -> expr = if (expr.startsWith("−")) expr.drop(1) else "−$expr"
            else -> {
                val ops = "÷×−+%"
                // Don't allow two operators in a row; swap the last one instead.
                expr = if (k in ops && expr.lastOrNull()?.toString()?.let { it in ops } == true) expr.dropLast(1) + k else expr + k
            }
        }
        show()
    }

    private fun show() {
        display.text = expr.ifEmpty { "0" }
        preview.text = eval(expr)?.takeIf { it != expr }?.let { "= $it" } ?: ""
    }

    /** Works out the sum with × and ÷ before + and −. Returns null if it isn't finished. */
    private fun eval(s: String): String? = runCatching {
        if (s.isBlank()) return null
        val tokens = mutableListOf<String>()
        var num = StringBuilder()
        s.forEachIndexed { i, c ->
            val unaryMinus = c == '−' && (i == 0 || s[i - 1] in "÷×−+")
            if (c.isDigit() || c == '.' || unaryMinus) num.append(if (c == '−') '-' else c)
            else if (c == '%') { tokens += BigDecimal(num.toString()).divide(BigDecimal(100)).toPlainString(); num = StringBuilder() }
            else { if (num.isNotEmpty()) { tokens += num.toString(); num = StringBuilder() }; tokens += c.toString() }
        }
        if (num.isNotEmpty()) tokens += num.toString()
        if (tokens.isEmpty() || tokens.last() in listOf("÷", "×", "−", "+")) return null
        // First pass: multiply and divide.
        val pass = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if ((t == "×" || t == "÷") && pass.isNotEmpty()) {
                val a = BigDecimal(pass.removeAt(pass.lastIndex)); val b = BigDecimal(tokens[i + 1])
                pass += (if (t == "×") a.multiply(b) else a.divide(b, MathContext.DECIMAL64)).toPlainString()
                i += 2
            } else { pass += t; i++ }
        }
        var total = BigDecimal(pass[0])
        i = 1
        while (i < pass.size) {
            val b = BigDecimal(pass[i + 1])
            total = if (pass[i] == "+") total.add(b) else total.subtract(b)
            i += 2
        }
        total.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace("-", "−")
    }.getOrNull()
}
