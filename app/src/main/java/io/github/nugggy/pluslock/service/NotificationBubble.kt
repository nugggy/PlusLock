package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.ui.ReplyActivity
import kotlin.math.abs

/**
 * The preview bubble shown with edge lighting. It lives in its own small window so it
 * can be tapped (open the message), swiped (dismiss) or replied to, while the glow
 * window underneath stays untouchable.
 */
class NotificationBubble(
    private val svc: PlusAccessibilityService,
    private var alert: LightAlert,
    private val topInset: Float,
    private val bottomInset: Float,
    private val onTouching: (Boolean) -> Unit,
    private val onGone: () -> Unit,
) {
    private var view: BubbleView? = null

    fun attach() {
        val screen = svc.screenBounds()
        val v = BubbleView(svc, alert, screen.width(), ::onTap, ::onReply, ::onSwiped, onTouching)
        val (w, h) = v.measureFor(screen.width())
        val lp = svc.overlayParams(w, h, touchable = true)
        lp.x = (screen.width() - w) / 2
        lp.y = if (P.bubblePosition.value == "BOTTOM") (screen.height() - bottomInset - h - svc.dp(28f)).toInt()
        else (topInset + svc.dp(10f)).toInt()
        if (svc.addOverlay(v, lp)) view = v
    }

    fun update(a: LightAlert) {
        alert = a
        val v = view ?: return
        v.setAlert(a)
        val screen = svc.screenBounds()
        val (w, h) = v.measureFor(screen.width())
        val lp = v.layoutParams as? android.view.WindowManager.LayoutParams ?: return
        lp.width = w; lp.height = h; lp.x = (screen.width() - w) / 2
        if (P.bubblePosition.value == "BOTTOM") lp.y = (screen.height() - bottomInset - h - svc.dp(28f)).toInt()
        runCatching { svc.wm.updateViewLayout(v, lp) }
        v.pop()
    }

    fun detach(immediate: Boolean) {
        val v = view ?: return
        view = null
        v.detaching = true
        if (immediate) { svc.removeOverlay(v); return }
        // Stop taking touches straight away, and remove it even if the fade gets cancelled.
        (v.layoutParams as? android.view.WindowManager.LayoutParams)?.let { lp ->
            lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { svc.wm.updateViewLayout(v, lp) }
        }
        v.animate().alpha(0f).setDuration(300).start()
        svc.handler.postDelayed({ svc.removeOverlay(v) }, 350)
    }

    private fun onTap() {
        val pi = alert.open
        if (pi != null && P.bubbleTapToOpen.value) {
            runCatching {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(svc, 0, null, null, null, null, opts.toBundle())
            }
        }
        onGone()
    }

    private fun onReply() {
        val key = alert.key ?: return onTap()
        runCatching {
            svc.startActivity(
                Intent(svc, ReplyActivity::class.java)
                    .putExtra(ReplyActivity.EXTRA_KEY, key)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        onGone()
    }

    private fun onSwiped() = onGone()
}

@SuppressLint("ViewConstructor")
private class BubbleView(
    ctx: Context,
    private var alert: LightAlert,
    screenW: Int,
    private val onTap: () -> Unit,
    private val onReply: () -> Unit,
    private val onSwiped: () -> Unit,
    private val onTouching: (Boolean) -> Unit,
) : View(ctx) {
    private val density = resources.displayMetrics.density
    // Bubble units follow the user's size setting and the system font size, like sp.
    private val unit = density * resources.configuration.fontScale * P.lightingPillSize.value / 100f
    private val style = P.bubbleStyle.value
    private val lines = P.bubbleLines.value.coerceIn(1, 3)
    private val maxW = screenW * 0.92f
    private val pad = 14 * unit
    private val iconSize = 30 * unit
    private val shadow = 6 * density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * unit; typeface = Typeface.DEFAULT_BOLD }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * unit }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 10 * unit; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val replyBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val replyText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * unit; typeface = Typeface.DEFAULT_BOLD }

    /** Set once the bubble is on its way out; it ignores touches from then on. */
    var detaching = false
    private var body: StaticLayout? = null
    private var titleLine = ""
    private var contentW = 0f
    private var bubbleH = 0f
    private val replyRect = RectF()
    private val showReply get() = alert.canReply && P.bubbleReply.value && alert.key != null && !alert.locked
    private val replyW get() = replyText.measureText("Reply") + 24 * unit

    init {
        alpha = 0f
        translationY = if (P.bubblePosition.value == "BOTTOM") 24 * density else -24 * density
        animate().alpha(1f).translationY(0f).setDuration(260).start()
        applyStyle()
    }

    fun setAlert(a: LightAlert) {
        alert = a
        applyStyle()
        requestLayout(); invalidate()
    }

    fun pop() {
        animate().scaleX(1.04f).scaleY(1.04f).setDuration(110).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    private fun applyStyle() {
        val c = alert.colour
        val (bgc, fg) = when (style) {
            "LIGHT" -> 0xF5F7F8FA.toInt() to 0xFF15171C.toInt()
            "GLASS" -> 0x8C101318.toInt() to Color.WHITE
            "APP" -> darken(c) to Color.WHITE
            else -> 0xEB101318.toInt() to Color.WHITE
        }
        bg.color = bgc
        edge.color = c
        titlePaint.color = fg
        textPaint.color = (fg and 0x00FFFFFF) or 0xD0000000.toInt()
        badgeBg.color = c
        replyBg.color = (c and 0x00FFFFFF) or 0x40000000
        replyText.color = if (style == "LIGHT") darken(c) else Color.WHITE
    }

    private fun darken(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2] * 0.42f
        return Color.HSVToColor(0xF2, hsv)
    }

    /** Works out the bubble's size for this alert and returns the window size. */
    fun measureFor(screenW: Int): Pair<Int, Int> {
        val replySpace = if (showReply) replyW + pad else 0f
        val textMax = (maxW - iconSize - pad * 3 - replySpace).coerceAtLeast(60 * density)
        val title = alert.title.orEmpty()
        titleLine = TextUtils.ellipsize(title, titlePaint, textMax, TextUtils.TruncateAt.END).toString()
        val text = alert.text.orEmpty().replace('\n', ' ')
        val natural = maxOf(titlePaint.measureText(titleLine), textPaint.measureText(text).coerceAtMost(textMax))
        contentW = natural.coerceAtMost(textMax)
        body = if (text.isBlank()) null else StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, contentW.toInt().coerceAtLeast(1))
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        val textH = titlePaint.fontSpacing + (body?.height?.toFloat() ?: 0f)
        bubbleH = maxOf(iconSize, textH) + pad * 2
        val w = (iconSize + pad * 3 + contentW + replySpace).coerceAtMost(maxW)
        return (w + shadow * 2).toInt() to (bubbleH + shadow * 2).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val left = shadow; val top = shadow
        val right = width - shadow; val bottom = top + bubbleH
        val r = RectF(left, top, right, bottom)
        val corner = if (lines == 1) bubbleH / 2 else 26 * unit
        canvas.drawRoundRect(r, corner, corner, bg)
        canvas.drawRoundRect(r, corner, corner, edge)

        val iy = top + (bubbleH - iconSize) / 2
        alert.icon?.let {
            val ix = (left + pad).toInt()
            it.setBounds(ix, iy.toInt(), ix + iconSize.toInt(), (iy + iconSize).toInt())
            it.draw(canvas)
        }
        if (alert.count > 1) {
            val bx = left + pad + iconSize - 2 * unit
            val by = iy + 3 * unit
            val br = 9 * unit
            canvas.drawCircle(bx, by, br, badgeBg)
            val label = if (alert.count > 99) "99+" else alert.count.toString()
            canvas.drawText(label, bx, by + badgeText.textSize / 3, badgeText)
        }

        val tx = left + pad * 2 + iconSize
        val textH = titlePaint.fontSpacing + (body?.height?.toFloat() ?: 0f)
        var ty = top + (bubbleH - textH) / 2
        canvas.drawText(titleLine, tx, ty - titlePaint.fontMetrics.ascent, titlePaint)
        ty += titlePaint.fontSpacing
        body?.let {
            canvas.save()
            canvas.translate(tx, ty)
            it.draw(canvas)
            canvas.restore()
        }

        if (showReply) {
            val rh = 32 * unit
            val rr = right - pad
            replyRect.set(rr - replyW, top + (bubbleH - rh) / 2, rr, top + (bubbleH + rh) / 2)
            canvas.drawRoundRect(replyRect, rh / 2, rh / 2, replyBg)
            replyText.textAlign = Paint.Align.CENTER
            canvas.drawText("Reply", replyRect.centerX(), replyRect.centerY() + replyText.textSize / 3, replyText)
        } else replyRect.setEmpty()
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (detaching) return false
        val slop = 10 * density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; moved = false
                onTouching(true)
                animate().cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (abs(dx) > slop || abs(dy) > slop) moved = true
                if (abs(dx) > abs(dy)) { translationX = dx; alpha = (1f - abs(dx) / width).coerceIn(0.2f, 1f) }
                else translationY = dy * 0.6f
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                val away = if (P.bubblePosition.value == "BOTTOM") dy > bubbleH * 0.5f else dy < -bubbleH * 0.5f
                when {
                    abs(dx) > width * 0.3f -> {
                        animate().translationX(if (dx > 0) width.toFloat() else -width.toFloat()).alpha(0f).setDuration(160)
                            .withEndAction(onSwiped).start()
                    }
                    away -> animate().translationY(if (dy > 0) bubbleH else -bubbleH).alpha(0f).setDuration(160)
                        .withEndAction(onSwiped).start()
                    !moved -> if (replyRect.contains(e.x, e.y)) onReply() else onTap()
                    else -> { animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160).start(); onTouching(false) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().translationX(0f).translationY(0f).alpha(1f).setDuration(160).start()
                onTouching(false)
            }
        }
        return true
    }
}
