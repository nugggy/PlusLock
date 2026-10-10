package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.P
import kotlin.math.max
import kotlin.math.sin

/**
 * What the camera island is showing. Fed by the notification listener (calls, timers, music)
 * and the battery watcher (charging), read by [Island] in the accessibility service.
 * Everything here runs on the main thread.
 */
object IslandState {
    data class Live(
        val kind: String, // CALL, TIMER, MEDIA, CHARGING
        val pkg: String,
        val title: String,
        val subtitle: String,
        val art: Bitmap? = null,
        val icon: Drawable? = null,
        /** Chronometer base in wall-clock ms, for calls and timers. */
        val base: Long = 0L,
        val countDown: Boolean = false,
        val playing: Boolean = false,
        val open: PendingIntent? = null,
        val controller: MediaController? = null,
    )

    private val ongoing = LinkedHashMap<String, Live>()
    var media: Live? = null
        private set
    private var charging: Live? = null
    private var chargingUntil = 0L
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
        ongoing.values.firstOrNull { it.kind == "CALL" && P.islandCalls.value }?.let { return it }
        ongoing.values.firstOrNull { it.kind == "TIMER" && P.islandTimers.value }?.let { return it }
        if (P.islandCharging.value && charging != null && SystemClock.elapsedRealtime() < chargingUntil) return charging
        media?.takeIf { P.islandMedia.value && it.playing }?.let { return it }
        return null
    }

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        val e = n.extras
        val kind = when {
            n.category == Notification.CATEGORY_CALL && sbn.isOngoing -> "CALL"
            sbn.isOngoing && e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) -> "TIMER"
            else -> null
        }
        if (kind == null) { if (ongoing.remove(sbn.key) != null) changed(); return }
        ongoing[sbn.key] = Live(
            kind = kind,
            pkg = sbn.packageName,
            title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { Apps.label(ctx, sbn.packageName) },
            subtitle = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            icon = Apps.icon(ctx, sbn.packageName),
            base = n.`when`,
            countDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            open = n.contentIntent,
        )
        changed()
    }

    fun onRemoved(key: String) {
        if (ongoing.remove(key) != null) changed()
    }

    fun setMedia(ctx: Context, c: MediaController?) {
        if (c == null) { media = null; changed(); return }
        val md = c.metadata
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        media = Live(
            kind = "MEDIA",
            pkg = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { Apps.label(ctx, c.packageName) },
            subtitle = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            icon = Apps.icon(ctx, c.packageName),
            playing = playing,
            open = c.sessionActivity,
            controller = c,
        )
        changed()
    }

    fun onPlugged(pct: Int, watts: Float) {
        charging = Live("CHARGING", "", "Charging", "$pct%" + if (watts >= 1f) " · %.0f W".format(watts) else "", base = pct.toLong())
        chargingUntil = SystemClock.elapsedRealtime() + 6_000
        changed()
    }

    fun chargingEndsIn(): Long = (chargingUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)
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
        v.setLive(live)
        // Charging only shows briefly, so look again when its time is up.
        if (live.kind == "CHARGING") svc.handler.postDelayed(recheck, IslandState.chargingEndsIn() + 50)
    }

    private fun create(): IslandView? {
        val b = svc.screenBounds()
        val cut = svc.wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects
            ?.filter { it.width() < b.width() / 3 }?.minByOrNull { it.top }
        val cx = cut?.exactCenterX() ?: (b.width() / 2f)
        val cy = cut?.exactCenterY() ?: svc.dp(20f)
        val hole = max(cut?.width() ?: 0, cut?.height() ?: 0).toFloat().takeIf { it > 0 } ?: svc.dp(24f)
        val v = IslandView(svc, hole, ::expandChanged)
        val lp = svc.overlayParams(v.compactW(), v.compactH(), touchable = true)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.x = (cx - v.compactW() / 2f).toInt()
        lp.y = (cy - v.compactH() / 2f).toInt()
        v.anchorX = cx; v.anchorY = cy
        if (!svc.addOverlay(v, lp)) return null
        view = v
        return v
    }

    private fun expandChanged(v: IslandView) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val b = svc.screenBounds()
        if (v.expanded) {
            lp.width = (b.width() * 0.92f).toInt(); lp.height = v.expandedH()
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
private class IslandView(ctx: Context, private val hole: Float, private val onExpand: (IslandView) -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    var anchorX = 0f
    var anchorY = 0f
    var expanded = false
        private set
    private var live: IslandState.Live? = null
    private val side = 66 * d
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.5f * d; typeface = Typeface.DEFAULT_BOLD }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16 * d; typeface = Typeface.DEFAULT_BOLD }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt(); textSize = 13.5f * d }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4BE38A.toInt() }
    private val btn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val buttons = Array(3) { RectF() }
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // The music bars animate; everything else only needs the clock ticking over.
            postDelayed(this, if (live?.kind == "MEDIA" && !expanded) 80 else 1000)
        }
    }
    private val collapse = Runnable { setExpanded(false) }

    fun compactH() = (hole + 10 * d).toInt().coerceAtLeast((30 * d).toInt())
    fun compactW() = (hole + side * 2).toInt()
    fun expandedH() = (compactH() + 96 * d).toInt()

    fun setLive(l: IslandState.Live) {
        val wasNull = live == null
        live = l
        if (wasNull) { alpha = 0f; animate().alpha(1f).setDuration(200).start(); post(tick) }
        invalidate()
    }

    fun stop() { removeCallbacks(tick); removeCallbacks(collapse) }

    private fun setExpanded(e: Boolean) {
        if (expanded == e) return
        expanded = e
        removeCallbacks(collapse)
        if (e) postDelayed(collapse, 6_000)
        onExpand(this)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val l = live ?: return
        val h = compactH().toFloat()
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, h / 2, h / 2, bg)
        if (expanded) drawExpanded(c, l) else drawCompact(c, l, h)
    }

    private fun drawCompact(c: Canvas, l: IslandState.Live, h: Float) {
        val iconSize = h - 10 * d
        val iy = (h - iconSize) / 2
        drawThumb(c, l, 8 * d, iy, iconSize)
        val label = compactLabel(l)
        val rx = width - 10 * d
        if (l.kind == "MEDIA") {
            // Three bouncing bars while music plays.
            val t = SystemClock.uptimeMillis() / 1000f
            for (i in 0..2) {
                val bh = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 7f + i * 1.9f))) * iconSize * 0.8f
                val x = rx - (2 - i) * 6 * d - 3 * d
                c.drawRoundRect(RectF(x, h / 2 - bh / 2, x + 3.5f * d, h / 2 + bh / 2), 2 * d, 2 * d, accent)
            }
        } else {
            text.textAlign = Paint.Align.RIGHT
            text.color = if (l.kind == "CALL") 0xFF4BE38A.toInt() else Color.WHITE
            c.drawText(label, rx, h / 2 + text.textSize / 3, text)
        }
    }

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
            else -> l.subtitle
        }
        c.drawText(TextUtils.ellipsize(subText, sub, maxW, TextUtils.TruncateAt.END).toString(), tx, top + 46 * d, sub)
        buttons.forEach { it.setEmpty() }
        if (l.kind == "MEDIA") {
            val size = 34 * d
            val cy = top + 74 * d
            val cx0 = tx + size / 2
            for (i in 0..2) {
                val cx = cx0 + i * (size + 22 * d)
                buttons[i].set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            }
            drawSkip(c, buttons[0], back = true)
            drawPlayPause(c, buttons[1], l.playing)
            drawSkip(c, buttons[2], back = false)
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { setExpanded(false); return false }
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val l = live ?: return true
        if (!expanded) { setExpanded(true); return true }
        val ctl = l.controller?.transportControls
        when {
            buttons[0].contains(e.x, e.y) -> ctl?.skipToPrevious()
            buttons[1].contains(e.x, e.y) -> if (l.playing) ctl?.pause() else ctl?.play()
            buttons[2].contains(e.x, e.y) -> ctl?.skipToNext()
            else -> {
                l.open?.let { pi ->
                    runCatching {
                        val opts = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        pi.send(context, 0, null, null, null, null, opts.toBundle())
                    }
                }
                setExpanded(false)
            }
        }
        removeCallbacks(collapse); postDelayed(collapse, 6_000)
        return true
    }
}
