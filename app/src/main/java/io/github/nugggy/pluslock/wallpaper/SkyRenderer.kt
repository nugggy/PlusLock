package io.github.nugggy.pluslock.wallpaper

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws soft drifting light blobs over a dark sky. "LIVING" picks colours from
 * the time of day, so the wallpaper slowly moves from dawn to day to dusk to night.
 */
class SkyRenderer {
    data class Palette(val bg: Int, val blobs: IntArray, val stars: Boolean)

    companion object {
        val themes = linkedMapOf(
            "LIVING" to "Living sky (follows the time of day)",
            "AURORA" to "Aurora",
            "EMBER" to "Ember",
            "OCEAN" to "Ocean",
            "BLOOM" to "Bloom",
            "MONO" to "Midnight mono",
        )

        private val NIGHT = Palette(0xFF03040C.toInt(), intArrayOf(0xFF1DE9B6.toInt(), 0xFF7C4DFF.toInt(), 0xFF00B0FF.toInt(), 0xFF64FFDA.toInt(), 0xFF3D5AFE.toInt()), true)
        private val DAWN = Palette(0xFF1A1030.toInt(), intArrayOf(0xFFFF8A80.toInt(), 0xFFFFC1A1.toInt(), 0xFFB388FF.toInt(), 0xFFFF80AB.toInt(), 0xFFFFD180.toInt()), false)
        private val DAY = Palette(0xFF0A2340.toInt(), intArrayOf(0xFF40C4FF.toInt(), 0xFF84FFFF.toInt(), 0xFF448AFF.toInt(), 0xFFB2EBF2.toInt(), 0xFF18FFFF.toInt()), false)
        private val DUSK = Palette(0xFF14081E.toInt(), intArrayOf(0xFFFF6E40.toInt(), 0xFFFF4081.toInt(), 0xFF7C4DFF.toInt(), 0xFFFFAB40.toInt(), 0xFFE040FB.toInt()), true)
        private val EMBER = Palette(0xFF0C0402.toInt(), intArrayOf(0xFFFF3D00.toInt(), 0xFFFF9100.toInt(), 0xFFD50000.toInt(), 0xFFFFC400.toInt(), 0xFFFF6D00.toInt()), false)
        private val OCEAN = Palette(0xFF010A12.toInt(), intArrayOf(0xFF00E5FF.toInt(), 0xFF2979FF.toInt(), 0xFF1DE9B6.toInt(), 0xFF00B8D4.toInt(), 0xFF304FFE.toInt()), false)
        private val BLOOM = Palette(0xFF0D0710.toInt(), intArrayOf(0xFFFF80AB.toInt(), 0xFFEA80FC.toInt(), 0xFFFFD180.toInt(), 0xFFB9F6CA.toInt(), 0xFF8C9EFF.toInt()), false)
        private val MONO = Palette(0xFF000000.toInt(), intArrayOf(0xFF9E9E9E.toInt(), 0xFF616161.toInt(), 0xFFBDBDBD.toInt(), 0xFF424242.toInt(), 0xFF757575.toInt()), true)

        fun paletteFor(theme: String, weather: Weather.Now? = null): Palette = when (theme) {
            "AURORA" -> NIGHT
            "EMBER" -> EMBER
            "OCEAN" -> OCEAN
            "BLOOM" -> BLOOM
            "MONO" -> MONO
            else -> if (weather != null && weather.sunrise > 0) bySun(weather) else when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..7 -> DAWN
                in 8..16 -> DAY
                in 17..19 -> DUSK
                else -> NIGHT
            }
        }

        /** Dawn and dusk follow today's real sunrise and sunset. */
        private fun bySun(w: Weather.Now): Palette {
            val now = System.currentTimeMillis()
            // Sunrise and sunset are for today; shift them to the current day if they're stale.
            val dayMs = 86_400_000L
            val shift = ((now - w.sunrise) / dayMs).coerceAtLeast(0) * dayMs
            val rise = w.sunrise + shift
            val set = w.sunset + shift
            val min = 60_000L
            return when (now) {
                in (rise - 45 * min) until (rise + 90 * min) -> DAWN
                in (rise + 90 * min) until (set - 90 * min) -> DAY
                in (set - 90 * min) until (set + 60 * min) -> DUSK
                else -> NIGHT
            }
        }
    }

    private class Ripple(val x: Float, val y: Float, val start: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SCREEN }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val stars = List(70) { floatArrayOf(Random.nextFloat(), Random.nextFloat() * 0.7f, Random.nextFloat() * 6.28f, 0.6f + Random.nextFloat()) }
    private val ripples = ArrayList<Ripple>()

    var theme = "LIVING"
    var speed = 1f
    var showStars = true
    /** Real weather for the living sky, or null to ignore it. */
    var weather: Weather.Now? = null

    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val drops = List(90) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat() * 0.8f) }

    fun touch(x: Float, y: Float, t: Float) {
        if (ripples.size > 6) ripples.removeAt(0)
        ripples += Ripple(x, y, t)
    }

    fun draw(c: Canvas, w: Int, h: Int, t: Float, tiltX: Float, tiltY: Float, scroll: Float) {
        val sky = if (theme == "LIVING") weather else null
        val pal = paletteFor(theme, sky)
        c.drawColor(pal.bg)
        val s = t * speed
        val big = max(w, h).toFloat()
        val density = w / 400f

        if (pal.stars && showStars && sky?.cloudy != true) {
            for (st in stars) {
                val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(s * 0.8f * st[3] + st[2]))
                starPaint.alpha = (a * 200).toInt()
                c.drawCircle(st[0] * w - tiltX * 6 * density, st[1] * h - tiltY * 6 * density, st[3] * 1.3f * density, starPaint)
            }
        }

        pal.blobs.forEachIndexed { i, colour ->
            val depth = (i + 1f) / pal.blobs.size
            val cx = w * (0.5f + 0.38f * sin(s * 0.11f * (i + 1) + i * 1.7f)) - tiltX * 30 * density * depth - scroll * w * 0.15f * depth
            val cy = h * (0.45f + 0.35f * cos(s * 0.09f * (i + 2) + i * 0.9f)) - tiltY * 30 * density * depth
            val r = big * (0.32f + 0.08f * sin(s * 0.2f + i))
            paint.shader = RadialGradient(cx, cy, r, (colour and 0x00FFFFFF) or 0x99000000.toInt(), colour and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
        }
        paint.shader = null

        if (sky != null) drawWeather(c, sky, s, density)

        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val age = t - r.start
            if (age > 1.6f) { it.remove(); continue }
            ripplePaint.color = pal.blobs[0]
            ripplePaint.alpha = ((1f - age / 1.6f) * 180).toInt()
            ripplePaint.strokeWidth = 3f * density
            c.drawCircle(r.x, r.y, age * 260 * density, ripplePaint)
        }
    }

    private fun drawWeather(c: Canvas, w: Weather.Now, s: Float, density: Float) {
        val width = c.width.toFloat(); val height = c.height.toFloat()
        // Cloud cover: a soft grey veil that mutes the colours.
        if (w.cloudy) c.drawColor(if (w.rain || w.snow) 0x40707884 else 0x2A8A9099)
        when {
            w.rain -> {
                dropPaint.strokeWidth = 1.4f * density
                for (d in drops) {
                    val y = ((d[1] + s * 0.55f * d[2]) % 1f) * (height + 60 * density) - 30 * density
                    val x = d[0] * width - y * 0.08f
                    dropPaint.alpha = (60 + 70 * d[2]).toInt()
                    c.drawLine(x, y, x - 3 * density, y + 18 * density * d[2], dropPaint)
                }
            }
            w.snow -> for (d in drops) {
                val y = ((d[1] + s * 0.06f * d[2]) % 1f) * height
                val x = d[0] * width + sin(s * 0.8f + d[0] * 20f) * 10 * density
                dropPaint.alpha = (110 + 90 * (d[2] - 0.6f)).toInt()
                c.drawCircle(x, y, 1.6f * density * d[2], dropPaint)
            }
        }
        // Thunderstorms: a brief flash every so often.
        if (w.storm && (s % 9f) < 0.12f) c.drawColor(0x55FFFFFF)
    }

    val hasRipples: Boolean get() = ripples.isNotEmpty()
}
