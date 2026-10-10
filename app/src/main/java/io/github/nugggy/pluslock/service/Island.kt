package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.P
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * What the camera island is showing. Fed by the notification listener (calls, timers, music,
 * downloads, directions and message peeks) and the battery watcher (charging), read by [Island]
 * in the accessibility service. Everything here runs on the main thread.
 */
object IslandState {
    data class Live(
        val kind: String, // CALL, PEEK, TIMER, NAV, PROGRESS, CHARGING, MEDIA
        val pkg: String,
        val title: String,
        val subtitle: String,
        val art: Bitmap? = null,
        val icon: Drawable? = null,
        /** Chronometer base in wall-clock ms for calls and timers; percent for progress and charging. */
        val base: Long = 0L,
        val countDown: Boolean = false,
        val playing: Boolean = false,
        val open: PendingIntent? = null,
        val controller: MediaController? = null,
        /** Glow colour: from the album art or the app. */
        val colour: Int = 0xFF4BE38A.toInt(),
        val durationMs: Long = 0L,
    )

    private val ongoing = LinkedHashMap<String, Live>()
    var media: Live? = null
        private set
    private var charging: Live? = null
    private var chargingUntil = 0L
    private var peek: Live? = null
    private var peekUntil = 0L
    private val listeners = mutableListOf<() -> Unit>()
    /** Set by the notification listener so the island can ask for the current music when it's switched on. */
    var requestMedia: (() -> Unit)? = null

    /** The listener reconnected: start again from the notifications that are showing now. */
    fun reset(ctx: Context, active: List<StatusBarNotification>) {
        ongoing.clear()
        active.forEach { onPosted(ctx, it) }
        changed()
    }

    fun observe(l: () -> Unit) { listeners += l }
    fun unobserve(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    /** The most important thing to show right now. */
    fun current(): Live? {
        val now = SystemClock.elapsedRealtime()
        ongoing.values.firstOrNull { it.kind == "CALL" && P.islandCalls.value }?.let { return it }
        if (peek != null && now < peekUntil) return peek
        ongoing.values.firstOrNull { it.kind == "TIMER" && P.islandTimers.value }?.let { return it }
        ongoing.values.firstOrNull { it.kind == "NAV" && P.islandNav.value }?.let { return it }
        if (P.islandCharging.value && charging != null && now < chargingUntil) return charging
        ongoing.values.lastOrNull { it.kind == "PROGRESS" && P.islandProgress.value }?.let { return it }
        media?.takeIf { P.islandMedia.value && it.playing }?.let { return it }
        return null
    }

    /** How long until something shown for a short while runs out, or -1. */
    fun nextExpiry(): Long {
        val now = SystemClock.elapsedRealtime()
        return listOf(peekUntil, chargingUntil).filter { it > now }.minOrNull()?.minus(now) ?: -1L
    }

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        val e = n.extras
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val kind = when {
            n.category == Notification.CATEGORY_CALL && sbn.isOngoing -> "CALL"
            n.category == Notification.CATEGORY_NAVIGATION && sbn.isOngoing -> "NAV"
            sbn.isOngoing && e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) -> "TIMER"
            sbn.isOngoing && max > 0 && !e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) -> "PROGRESS"
            else -> null
        }
        if (kind == null) { if (ongoing.remove(sbn.key) != null) changed(); return }
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { Apps.label(ctx, sbn.packageName) }
        ongoing[sbn.key] = Live(
            kind = kind,
            pkg = sbn.packageName,
            title = title,
            subtitle = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            icon = Apps.icon(ctx, sbn.packageName),
            base = if (kind == "PROGRESS") e.getInt(Notification.EXTRA_PROGRESS, 0) * 100L / max else n.`when`,
            countDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            open = n.contentIntent,
            colour = Apps.accentColour(ctx, sbn.packageName),
        )
        changed()
    }

    fun onRemoved(key: String) {
        if (ongoing.remove(key) != null) changed()
    }

    /** A new message: show who it's from for a few seconds. */
    fun peek(ctx: Context, pkg: String, title: String, text: String, open: PendingIntent?, colour: Int) {
        if (!P.islandOn.value || !P.islandPeek.value) return
        peek = Live("PEEK", pkg, title, text, icon = Apps.icon(ctx, pkg), open = open, colour = colour)
        peekUntil = SystemClock.elapsedRealtime() + P.islandPeekSeconds.value * 1000L
        changed()
    }

    fun setMedia(ctx: Context, c: MediaController?) {
        if (c == null) { media = null; changed(); return }
        val md = c.metadata
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        media = Live(
            kind = "MEDIA",
            pkg = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { Apps.label(ctx, c.packageName) },
            subtitle = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            art = art,
            icon = Apps.icon(ctx, c.packageName),
            playing = playing,
            open = c.sessionActivity,
            controller = c,
            colour = art?.let { artColour(it) } ?: Apps.accentColour(ctx, c.packageName),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
        changed()
    }

    /** The album art's overall colour, brightened so it glows. */
    private fun artColour(b: Bitmap): Int = runCatching {
        val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        Apps.vivid(px)
    }.getOrDefault(0xFF4BE38A.toInt())

    fun onPlugged(pct: Int, watts: Float) {
        charging = Live("CHARGING", "", "Charging", "$pct%" + if (watts >= 1f) " · %.0f W".format(watts) else "", base = pct.toLong())
        chargingUntil = SystemClock.elapsedRealtime() + 6_000
        changed()
    }
}

/** A pill around the punch-hole camera, Dynamic Island style. Tap it to expand. */
class Island(private val svc: PlusAccessibilityService) {
    private var view: IslandView? = null
    private val onChange: () -> Unit = { render() }
    private var observing = false
    private val recheck = Runnable { render() }

    fun refresh() {
        remove()
        if (!P.islandOn.value) {
            if (observing) { IslandState.unobserve(onChange); observing = false }
            return
        }
        if (!observing) { IslandState.observe(onChange); observing = true }
        IslandState.requestMedia?.invoke()
        if (!svc.getSystemService(android.os.PowerManager::class.java).isInteractive) return
        render()
    }

    private fun render() {
        svc.handler.removeCallbacks(recheck)
        val live = IslandState.current()
        val awake = svc.getSystemService(android.os.PowerManager::class.java).isInteractive
        if (live == null || svc.isLandscape || !P.islandOn.value || !awake) { remove(); return }
        val v = view ?: create() ?: return
        val wide = v.isWide()
        v.setLive(live)
        // Messages and directions need more room than the usual pill.
        if (wide != v.isWide() && !v.expanded) layout(v)
        // Peeks and charging only show briefly, so look again when their time is up.
        IslandState.nextExpiry().takeIf { it >= 0 }?.let { svc.handler.postDelayed(recheck, it + 50) }
    }

    private fun create(): IslandView? {
        val b = svc.screenBounds()
        val cut = svc.wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects
            ?.filter { it.width() < b.width() / 3 }?.minByOrNull { it.top }
        val cx = cut?.exactCenterX() ?: (b.width() / 2f)
        val cy = cut?.exactCenterY() ?: svc.dp(20f)
        val hole = max(cut?.width() ?: 0, cut?.height() ?: 0).toFloat().takeIf { it > 0 } ?: svc.dp(24f)
        val v = IslandView(svc, hole, ::layout)
        v.anchorX = cx; v.anchorY = cy
        val lp = svc.overlayParams(v.compactW(), v.compactH(), touchable = true)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.x = (cx - v.compactW() / 2f).toInt()
        lp.y = (cy - v.compactH() / 2f).toInt()
        if (!svc.addOverlay(v, lp)) return null
        view = v
        return v
    }

    private fun layout(v: IslandView) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val b = svc.screenBounds()
        if (v.expanded) {
            lp.width = (b.width() * 0.94f).toInt(); lp.height = v.expandedH()
            lp.x = (b.width() - lp.width) / 2; lp.y = (v.anchorY - v.compactH() / 2f).toInt()
        } else {
            lp.width = v.compactW(); lp.height = v.compactH()
            lp.x = (v.anchorX - lp.width / 2f).toInt(); lp.y = (v.anchorY - lp.height / 2f).toInt()
        }
        runCatching { svc.wm.updateViewLayout(v, lp) }
    }

    fun remove() {
        view?.stop()
        svc.removeOverlay(view)
        view = null
    }

    /** The service is going away: stop listening to the shared state too. */
    fun release() {
        remove()
        svc.handler.removeCallbacks(recheck)
        if (observing) { IslandState.unobserve(onChange); observing = false }
    }
}

@SuppressLint("ViewConstructor")
private class IslandView(ctx: Context, private val hole: Float, private val onResize: (IslandView) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val size = P.islandSize.value / 100f
    var anchorX = 0f
    var anchorY = 0f
    var expanded = false
        private set
    private var live: IslandState.Live? = null
    private val side = 66 * d * size
    private val glowOn = P.islandGlow.value
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.5f * d * size; typeface = Typeface.DEFAULT_BOLD }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16 * d; typeface = Typeface.DEFAULT_BOLD }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textSize = 13.5f * d }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textSize = 11 * d }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4BE38A.toInt() }
    private val btn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val buttons = Array(3) { RectF() }
    private val seekBar = RectF()
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // Music bars and the glow animate; everything else only needs the clock ticking over.
            postDelayed(this, if ((live?.kind == "MEDIA" && !expanded) || glowOn) 60 else 1000)
        }
    }
    private val collapse = Runnable { setExpanded(false) }

    fun compactH() = ((hole + 10 * d) * size).toInt().coerceAtLeast((30 * d).toInt())
    fun isWide() = live?.kind == "PEEK" || live?.kind == "NAV"
    fun compactW() = (hole + side * 2 + if (isWide()) 120 * d * size else 0f).toInt()
    fun expandedH() = (compactH() + (if (live?.kind == "MEDIA") 128 else 96) * d).toInt()

    fun setLive(l: IslandState.Live) {
        val wasNull = live == null
        val kindChanged = live?.kind != l.kind
        live = l
        accent.color = l.colour
        if (wasNull) { alpha = 0f; animate().alpha(1f).setDuration(200).start(); post(tick) }
        if (kindChanged && expanded) onResize(this)
        invalidate()
    }

    fun stop() { removeCallbacks(tick); removeCallbacks(collapse) }

    private fun setExpanded(e: Boolean) {
        if (expanded == e) return
        expanded = e
        removeCallbacks(collapse)
        if (e) postDelayed(collapse, P.islandCollapseSeconds.value * 1000L)
        onResize(this)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val l = live ?: return
        val h = compactH().toFloat()
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val corner = if (expanded) 28 * d else h / 2
        c.drawRoundRect(r, corner, corner, bg)
        if (glowOn) {
            // A rim of light in the app's or album's colour that breathes.
            val t = SystemClock.uptimeMillis() / 1000f
            rim.color = l.colour
            rim.alpha = (120 + 100 * (0.5f + 0.5f * sin(t * 2.5f))).toInt()
            r.inset(1 * d, 1 * d)
            c.drawRoundRect(r, corner, corner, rim)
        }
        if (expanded) drawExpanded(c, l) else drawCompact(c, l, h)
    }

    private fun drawCompact(c: Canvas, l: IslandState.Live, h: Float) {
        val iconSize = h - 10 * d
        val iy = (h - iconSize) / 2
        drawThumb(c, l, 8 * d, iy, iconSize)
        val rx = width - 10 * d
        when (l.kind) {
            "MEDIA" -> {
                // Bars that bounce while music plays.
                val t = SystemClock.uptimeMillis() / 1000f
                for (i in 0..2) {
                    val bh = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 7f + i * 1.9f))) * iconSize * 0.8f
                    val x = rx - (2 - i) * 6 * d - 3 * d
                    c.drawRoundRect(RectF(x, h / 2 - bh / 2, x + 3.5f * d, h / 2 + bh / 2), 2 * d, 2 * d, accent)
                }
            }
            "PROGRESS", "CHARGING" -> {
                // A progress ring with the percentage beside it.
                val rr = iconSize * 0.36f
                val cx = rx - rr
                ring.strokeWidth = 3 * d; ring.color = 0x33FFFFFF
                c.drawCircle(cx, h / 2, rr, ring)
                ring.color = l.colour
                c.drawArc(RectF(cx - rr, h / 2 - rr, cx + rr, h / 2 + rr), -90f, 3.6f * l.base, false, ring)
                text.textAlign = Paint.Align.RIGHT; text.color = Color.WHITE
                c.drawText("${l.base}%", cx - rr - 6 * d, h / 2 + text.textSize / 3, text)
            }
            "PEEK", "NAV" -> {
                text.textAlign = Paint.Align.LEFT; text.color = Color.WHITE
                val tx = 8 * d + iconSize + 8 * d
                val label = if (l.kind == "PEEK") l.title else l.subtitle.ifBlank { l.title }
                c.drawText(TextUtils.ellipsize(label, text, width - tx - 12 * d, TextUtils.TruncateAt.END).toString(), tx, h / 2 + text.textSize / 3, text)
            }
            else -> {
                text.textAlign = Paint.Align.RIGHT
                text.color = if (l.kind == "CALL") 0xFF4BE38A.toInt() else Color.WHITE
                c.drawText(compactLabel(l), rx, h / 2 + text.textSize / 3, text)
            }
        }
    }

    private fun position(l: IslandState.Live): Long {
        val st = l.controller?.playbackState ?: return 0
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING) p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        return p.coerceIn(0, if (l.durationMs > 0) l.durationMs else Long.MAX_VALUE)
    }

    private fun mmss(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun drawExpanded(c: Canvas, l: IslandState.Live) {
        val pad = 16 * d
        val top = compactH().toFloat()
        val art = 56 * d
        drawThumb(c, l, pad, top + 4 * d, art)
        val tx = pad * 2 + art
        val maxW = width - tx - pad
        c.drawText(TextUtils.ellipsize(l.title, title, maxW, TextUtils.TruncateAt.END).toString(), tx, top + 24 * d, title)
        val subText = when (l.kind) {
            "CALL", "TIMER" -> compactLabel(l) + if (l.subtitle.isNotBlank()) "  ·  " + l.subtitle else ""
            "PROGRESS" -> "${l.base}%" + if (l.subtitle.isNotBlank()) "  ·  " + l.subtitle else ""
            else -> l.subtitle
        }
        c.drawText(TextUtils.ellipsize(subText, sub, maxW, TextUtils.TruncateAt.END).toString(), tx, top + 46 * d, sub)
        buttons.forEach { it.setEmpty() }
        seekBar.setEmpty()
        if (l.kind == "MEDIA") {
            // A progress bar you can tap to jump around the track.
            if (l.durationMs > 0) {
                val y = top + 76 * d
                seekBar.set(pad, y - 10 * d, width - pad, y + 10 * d)
                val f = position(l).toFloat() / l.durationMs
                accent.alpha = 70; c.drawRoundRect(RectF(pad, y - 2 * d, width - pad, y + 2 * d), 2 * d, 2 * d, accent)
                accent.alpha = 255; c.drawRoundRect(RectF(pad, y - 2 * d, pad + (width - 2 * pad) * f, y + 2 * d), 2 * d, 2 * d, accent)
                c.drawCircle(pad + (width - 2 * pad) * f, y, 5 * d, btn)
                small.textAlign = Paint.Align.LEFT; c.drawText(mmss(position(l)), pad, y + 18 * d, small)
                small.textAlign = Paint.Align.RIGHT; c.drawText(mmss(l.durationMs), width - pad, y + 18 * d, small)
            }
            val bsize = 36 * d
            val cy = top + (if (l.durationMs > 0) 112 else 76) * d
            val gap = 30 * d
            val cx0 = width / 2f - bsize - gap
            for (i in 0..2) {
                val cx = cx0 + i * (bsize + gap)
                buttons[i].set(cx - bsize / 2, cy - bsize / 2, cx + bsize / 2, cy + bsize / 2)
            }
            drawSkip(c, buttons[0], back = true)
            drawPlayPause(c, buttons[1], l.playing)
            drawSkip(c, buttons[2], back = false)
        } else if (l.kind == "PROGRESS") {
            val y = top + 74 * d
            accent.alpha = 70; c.drawRoundRect(RectF(pad, y - 3 * d, width - pad, y + 3 * d), 3 * d, 3 * d, accent)
            accent.alpha = 255; c.drawRoundRect(RectF(pad, y - 3 * d, pad + (width - 2 * pad) * l.base / 100f, y + 3 * d), 3 * d, 3 * d, accent)
        }
    }

    private fun drawThumb(c: Canvas, l: IslandState.Live, x: Float, y: Float, size: Float) {
        val art = l.art
        if (art != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(size / art.width, size / art.height); postTranslate(x, y) })
            }
            c.drawRoundRect(RectF(x, y, x + size, y + size), size * 0.25f, size * 0.25f, p)
        } else if (l.kind == "CHARGING") {
            val p = Path().apply {
                // A lightning bolt.
                moveTo(x + size * 0.58f, y + size * 0.08f); lineTo(x + size * 0.22f, y + size * 0.56f)
                lineTo(x + size * 0.48f, y + size * 0.56f); lineTo(x + size * 0.40f, y + size * 0.92f)
                lineTo(x + size * 0.78f, y + size * 0.42f); lineTo(x + size * 0.52f, y + size * 0.42f); close()
            }
            c.drawPath(p, accent)
        } else l.icon?.let {
            it.setBounds(x.toInt(), y.toInt(), (x + size).toInt(), (y + size).toInt())
            it.draw(c)
        }
    }

    private fun compactLabel(l: IslandState.Live): String = when (l.kind) {
        "CALL", "TIMER" -> {
            val ms = if (l.countDown) l.base - System.currentTimeMillis() else System.currentTimeMillis() - l.base
            val s = (ms / 1000).coerceAtLeast(0)
            if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
        "CHARGING" -> l.subtitle.substringBefore(" ·")
        else -> ""
    }

    private fun drawPlayPause(c: Canvas, r: RectF, playing: Boolean) {
        c.drawCircle(r.centerX(), r.centerY(), r.width() / 2, btn)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val s = r.width() * 0.22f
        if (playing) {
            c.drawRect(r.centerX() - s, r.centerY() - s, r.centerX() - s * 0.3f, r.centerY() + s, p)
            c.drawRect(r.centerX() + s * 0.3f, r.centerY() - s, r.centerX() + s, r.centerY() + s, p)
        } else {
            c.drawPath(Path().apply {
                moveTo(r.centerX() - s * 0.7f, r.centerY() - s); lineTo(r.centerX() + s, r.centerY()); lineTo(r.centerX() - s * 0.7f, r.centerY() + s); close()
            }, p)
        }
    }

    private fun drawSkip(c: Canvas, r: RectF, back: Boolean) {
        val s = r.width() * 0.22f
        val dir = if (back) -1 else 1
        val cx = r.centerX(); val cy = r.centerY()
        c.drawPath(Path().apply {
            moveTo(cx - dir * s, cy - s); lineTo(cx + dir * s * 0.6f, cy); lineTo(cx - dir * s, cy + s); close()
        }, btn)
        c.drawRect(minOf(cx + dir * s * 0.6f, cx + dir * s), cy - s, maxOf(cx + dir * s * 0.6f, cx + dir * s), cy + s, btn)
    }

    private fun openApp(l: IslandState.Live) {
        l.open?.let { pi ->
            runCatching {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            }
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { setExpanded(false); return false }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y; downAt = SystemClock.uptimeMillis(); return true }
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val l = live ?: return true
        val dx = e.x - downX
        val held = SystemClock.uptimeMillis() - downAt
        val gestures = P.islandGestures.value
        if (!expanded) {
            when {
                // Swipe the pill sideways to skip tracks.
                gestures && l.kind == "MEDIA" && abs(dx) > 36 * d -> {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (dx < 0) l.controller?.transportControls?.skipToNext() else l.controller?.transportControls?.skipToPrevious()
                }
                // Hold to jump straight into the app.
                gestures && held > 450 && abs(dx) < 16 * d -> { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); openApp(l) }
                // A message peek opens the chat on a tap.
                l.kind == "PEEK" -> openApp(l)
                else -> setExpanded(true)
            }
            return true
        }
        val ctl = l.controller?.transportControls
        when {
            buttons[0].contains(e.x, e.y) -> ctl?.skipToPrevious()
            buttons[1].contains(e.x, e.y) -> if (l.playing) ctl?.pause() else ctl?.play()
            buttons[2].contains(e.x, e.y) -> ctl?.skipToNext()
            seekBar.contains(e.x, e.y) && l.durationMs > 0 -> {
                val f = ((e.x - seekBar.left) / seekBar.width()).coerceIn(0f, 1f)
                ctl?.seekTo((l.durationMs * f).toLong())
            }
            else -> { openApp(l); setExpanded(false) }
        }
        removeCallbacks(collapse); postDelayed(collapse, P.islandCollapseSeconds.value * 1000L)
        return true
    }

    init {
        // A soft blur on the rim so the glow reads as light rather than a line.
        rim.maskFilter = BlurMaskFilter(1.5f * d, BlurMaskFilter.Blur.NORMAL)
    }
}
