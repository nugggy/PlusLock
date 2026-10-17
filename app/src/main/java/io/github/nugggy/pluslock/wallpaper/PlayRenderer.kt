package io.github.nugggy.pluslock.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Wallpapers you can play with: they react to your finger, tilting the phone and the battery. */
class PlayRenderer {
    companion object {
        val themes = linkedMapOf(
            "SWARM" to "Swarm: particles scatter from your finger",
            "RIPPLES" to "Ripples: touch the water",
            "FIREFLIES" to "Fireflies: they gather where you touch",
            "TRAILS" to "Neon trails: draw with light",
            "CONSTELLATION" to "Constellation: stars link up round your finger",
            "MARBLES" to "Marbles: tilt to roll them, tap for more",
            "TIDE" to "Battery tide: sloshes when you tilt, at your battery level",
            "LAVA" to "Lava lamp: blobs drift towards your touch",
        )
        /** Themes that need the tilt sensor to be any fun. */
        val tiltThemes = setOf("MARBLES", "TIDE", "SWARM", "LAVA")

        /** Each wallpaper's own colours, and the starting point for a mix of your own. */
        val themePalette = mapOf(
            "SWARM" to intArrayOf(0xFF7FB2FF.toInt(), 0xFFB388FF.toInt(), 0xFF5EE4FF.toInt()),
            "RIPPLES" to intArrayOf(0xFF5EE4FF.toInt(), 0xFF3D7BFF.toInt(), 0xFF00E5B0.toInt()),
            "FIREFLIES" to intArrayOf(0xFFFFF35E.toInt(), 0xFFA6FF4D.toInt(), 0xFFFFC75E.toInt()),
            "TRAILS" to intArrayOf(0xFFFF3D8A.toInt(), 0xFF5EE4FF.toInt(), 0xFFB388FF.toInt()),
            "CONSTELLATION" to intArrayOf(0xFFFFFFFF.toInt(), 0xFF7FB2FF.toInt(), 0xFFFFC75E.toInt()),
            "MARBLES" to intArrayOf(0xFFFF5E5E.toInt(), 0xFFFFC75E.toInt(), 0xFF4BE38A.toInt(), 0xFF7FB2FF.toInt(), 0xFFB388FF.toInt()),
            "TIDE" to intArrayOf(0xFF4BE38A.toInt(), 0xFF5EE4FF.toInt()),
            "LAVA" to intArrayOf(0xFFFF8A3D.toInt(), 0xFFFF3D8A.toInt(), 0xFFFFC75E.toInt()),
        )

        const val MAX_COLOURS = 8
    }

    var theme = "SWARM"
        set(v) { if (field != v) { field = v; reset = true } }
    var colourMode = "THEME" // THEME, MINE, CUSTOM, RAINBOW
    var colour = 0xFF7FB2FF.toInt()
    /** Your own mix, used in CUSTOM mode. */
    var colours = intArrayOf(0xFF7FB2FF.toInt())
    var density = 1f
        set(v) { if (field != v) { field = v; reset = true } }
    var speed = 1f
    var blackBackground = true
    /** 0 to 1. */
    var battery = 0.6f

    private var reset = true
    private var w = 0; private var h = 0
    private var lastT = 0f
    private val rnd = Random(7)

    // Touch.
    private var touching = false
    private var tx = 0f; private var ty = 0f
    private var lastTouchAt = -10f
    private var lastSpawn = 0f

    // Particles: x, y, vx, vy, and a home or phase per particle.
    private var px = FloatArray(0); private var py = FloatArray(0)
    private var vx = FloatArray(0); private var vy = FloatArray(0)
    private var hx = FloatArray(0); private var hy = FloatArray(0)
    private var ph = FloatArray(0)
    private var radius = FloatArray(0)
    private var count = 0

    private data class Ripple(val x: Float, val y: Float, val t0: Float, val strength: Float)
    private val ripples = ArrayList<Ripple>()
    private data class Pt(val x: Float, val y: Float, val t: Float)
    private val trail = ArrayList<Pt>()
    private var autoAngle = 0f

    // Tide.
    private var slope = 0f
    private var slopeV = 0f
    private val splash = FloatArray(48)
    private val splashV = FloatArray(48)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) }
    private val path = Path()
    private val hsv = FloatArray(3)

    fun touch(e: MotionEvent, t: Float) {
        tx = e.x; ty = e.y
        lastTouchAt = t
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; onTap(e.x, e.y, t) }
            MotionEvent.ACTION_MOVE -> { touching = true; onDrag(e.x, e.y, t) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
    }

    private fun onTap(x: Float, y: Float, t: Float) {
        when (theme) {
            "RIPPLES" -> ripples += Ripple(x, y, t, 1f)
            "MARBLES" -> addMarble(x, y)
            "TRAILS" -> trail += Pt(x, y, t)
            "TIDE" -> poke(x, 1f)
        }
    }

    private fun onDrag(x: Float, y: Float, t: Float) {
        when (theme) {
            "RIPPLES" -> if (t - lastSpawn > 0.09f) { ripples += Ripple(x, y, t, 0.6f); lastSpawn = t }
            "TRAILS" -> trail += Pt(x, y, t)
            "TIDE" -> poke(x, 0.35f)
        }
    }

    private fun poke(x: Float, amount: Float) {
        if (w == 0) return
        val i = ((x / w) * (splash.size - 1)).toInt().coerceIn(0, splash.size - 1)
        splashV[i] -= h * 0.02f * amount
    }

    // ---- colours ----

    private fun colourFor(i: Int, t: Float): Int = when (colourMode) {
        "RAINBOW" -> Color.HSVToColor(floatArrayOf(((i * 37f) + t * 30f) % 360f, 0.7f, 1f))
        "MINE" -> {
            Color.colorToHSV(colour, hsv)
            hsv[0] = (hsv[0] + ((i % 5) - 2) * 9f + 360f) % 360f
            Color.HSVToColor(hsv)
        }
        "CUSTOM" -> colours.let { if (it.isEmpty()) colour else it[i % it.size] }
        else -> themePalette[theme]!!.let { it[i % it.size] }
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    // ---- set up ----

    private fun setup() {
        reset = false
        ripples.clear(); trail.clear()
        count = when (theme) {
            "SWARM" -> (260 * density).toInt()
            "FIREFLIES" -> (55 * density).toInt()
            "CONSTELLATION" -> (70 * density).toInt()
            "MARBLES" -> (22 * density).toInt()
            "LAVA" -> (6 * density).toInt().coerceIn(3, 14)
            "TIDE" -> (24 * density).toInt()
            else -> 0
        }.coerceAtLeast(0)
        px = FloatArray(count); py = FloatArray(count); vx = FloatArray(count); vy = FloatArray(count)
        hx = FloatArray(count); hy = FloatArray(count); ph = FloatArray(count); radius = FloatArray(count)
        val unit = min(w, h).toFloat()
        for (i in 0 until count) {
            px[i] = rnd.nextFloat() * w; py[i] = rnd.nextFloat() * h
            hx[i] = px[i]; hy[i] = py[i]
            ph[i] = rnd.nextFloat() * 100f
            radius[i] = when (theme) {
                "MARBLES" -> unit * (0.035f + rnd.nextFloat() * 0.035f)
                "LAVA" -> unit * (0.18f + rnd.nextFloat() * 0.16f)
                "SWARM" -> unit * (0.004f + rnd.nextFloat() * 0.006f)
                else -> unit * 0.006f
            }
        }
        splash.fill(0f); splashV.fill(0f)
    }

    private fun addMarble(x: Float, y: Float) {
        if (count >= 70) return
        val unit = min(w, h).toFloat()
        px += x; py += y; vx += 0f; vy += 0f; hx += 0f; hy += 0f; ph += rnd.nextFloat() * 100f
        radius += unit * (0.035f + rnd.nextFloat() * 0.035f)
        count++
    }

    // ---- frame ----

    /** gx, gy: tilt from the accelerometer, roughly -10 to 10. */
    fun draw(c: Canvas, width: Int, height: Int, t: Float, gx: Float, gy: Float) {
        if (width != w || height != h) { w = width; h = height; reset = true }
        if (reset) setup()
        val dt = ((t - lastT).coerceIn(0f, 0.05f)) * speed
        lastT = t
        background(c, t)
        when (theme) {
            "RIPPLES" -> ripplesFrame(c, t)
            "FIREFLIES" -> firefliesFrame(c, t, dt)
            "TRAILS" -> trailsFrame(c, t)
            "CONSTELLATION" -> constellationFrame(c, t, dt)
            "MARBLES" -> marblesFrame(c, t, dt, gx, gy)
            "TIDE" -> tideFrame(c, t, dt, gx)
            "LAVA" -> lavaFrame(c, t, dt, gx, gy)
            else -> swarmFrame(c, t, dt, gx, gy)
        }
    }

    private fun background(c: Canvas, t: Float) {
        if (blackBackground) { c.drawColor(Color.BLACK); return }
        val top = when (theme) {
            "RIPPLES" -> 0xFF04121F.toInt(); "LAVA" -> 0xFF1A0610.toInt(); "FIREFLIES" -> 0xFF050F08.toInt()
            "TIDE" -> 0xFF06100C.toInt(); else -> 0xFF080A14.toInt()
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        fill.shader = null
    }

    private fun swarmFrame(c: Canvas, t: Float, dt: Float, gx: Float, gy: Float) {
        val reach = min(w, h) * 0.28f
        fill.shader = null; fill.maskFilter = null
        for (i in 0 until count) {
            // Homes drift slowly so the swarm is always alive, and lean with the tilt.
            val homeX = hx[i] + sin(t * 0.3f + ph[i]) * w * 0.03f - gx * w * 0.01f
            val homeY = hy[i] + cos(t * 0.25f + ph[i] * 1.3f) * h * 0.02f + gy * h * 0.005f
            var ax = (homeX - px[i]) * 1.6f
            var ay = (homeY - py[i]) * 1.6f
            if (touching) {
                val dx = px[i] - tx; val dy = py[i] - ty
                val d = hypot(dx, dy).coerceAtLeast(1f)
                if (d < reach) {
                    val f = (1 - d / reach) * 9000f
                    ax += dx / d * f; ay += dy / d * f
                }
            }
            vx[i] = (vx[i] + ax * dt) * 0.9f
            vy[i] = (vy[i] + ay * dt) * 0.9f
            px[i] += vx[i] * dt; py[i] += vy[i] * dt
            val sp = min(1f, hypot(vx[i], vy[i]) / 600f)
            fill.color = withAlpha(colourFor(i, t), 0.45f + 0.55f * sp)
            c.drawCircle(px[i], py[i], radius[i] * (1f + sp), fill)
        }
    }

    private fun ripplesFrame(c: Canvas, t: Float) {
        // A raindrop now and then, so it's never still.
        if (t - lastTouchAt > 2f && rnd.nextFloat() < 0.02f * speed) ripples += Ripple(rnd.nextFloat() * w, rnd.nextFloat() * h, t, 0.4f)
        ripples.removeAll { t - it.t0 > 3.2f }
        line.shader = null
        val maxR = max(w, h) * 0.6f
        ripples.forEachIndexed { k, r ->
            val age = (t - r.t0) * speed
            for (ring in 0 until 3) {
                val a = age - ring * 0.18f
                if (a <= 0) continue
                val rad = maxR * (1 - (1 - min(a / 3f, 1f)).let { it * it * it })
                val alpha = (1 - a / 3f) * r.strength * (1f - ring * 0.25f)
                if (alpha <= 0) continue
                line.color = withAlpha(colourFor(k + ring, t), alpha)
                line.strokeWidth = min(w, h) * 0.006f * (1.5f - ring * 0.4f)
                c.drawCircle(r.x, r.y, rad, line)
            }
        }
    }

    private fun firefliesFrame(c: Canvas, t: Float, dt: Float) {
        val unit = min(w, h).toFloat()
        val calling = touching || t - lastTouchAt < 2.5f
        fill.shader = null
        for (i in 0 until count) {
            val wander = t * 0.6f + ph[i]
            var ax = cos(wander * 1.3f) * 60f + sin(wander * 0.7f) * 40f
            var ay = sin(wander * 1.1f) * 60f + cos(wander * 0.5f) * 40f
            if (calling) {
                val dx = tx - px[i]; val dy = ty - py[i]
                val d = hypot(dx, dy).coerceAtLeast(1f)
                ax += dx / d * 260f; ay += dy / d * 260f
                // Orbit rather than pile up on the finger.
                ax += -dy / d * 140f; ay += dx / d * 140f
            }
            vx[i] = (vx[i] + ax * dt) * 0.96f; vy[i] = (vy[i] + ay * dt) * 0.96f
            px[i] = (px[i] + vx[i] * dt + w) % w; py[i] = (py[i] + vy[i] * dt + h) % h
            val blink = (0.5f + 0.5f * sin(t * 2.2f + ph[i] * 3f)).let { it * it }
            val col = colourFor(i, t)
            fill.color = withAlpha(col, 0.18f * blink)
            c.drawCircle(px[i], py[i], unit * 0.03f, fill)
            fill.color = withAlpha(col, 0.35f + 0.65f * blink)
            c.drawCircle(px[i], py[i], unit * 0.006f, fill)
        }
    }

    private fun trailsFrame(c: Canvas, t: Float) {
        val life = 1.6f
        // Left alone, it doodles by itself.
        if (t - lastTouchAt > 3f) {
            autoAngle += 0.035f * speed
            trail += Pt(w / 2f + sin(autoAngle * 2f) * w * 0.35f, h / 2f + sin(autoAngle * 3f) * h * 0.25f, t)
        }
        trail.removeAll { t - it.t > life }
        if (trail.size > 600) trail.subList(0, trail.size - 600).clear()
        val unit = min(w, h)
        for (i in 1 until trail.size) {
            val a = trail[i - 1]; val b = trail[i]
            if (hypot(b.x - a.x, b.y - a.y) > unit * 0.25f) continue
            val fade = 1 - (t - b.t) / life
            val col = if (colourMode == "THEME") Color.HSVToColor(floatArrayOf((b.t * 90f) % 360f, 0.75f, 1f)) else colourFor(i / 6, t)
            glow.color = withAlpha(col, 0.25f * fade); glow.strokeWidth = unit * 0.035f * fade
            c.drawLine(a.x, a.y, b.x, b.y, glow)
            line.color = withAlpha(Color.WHITE, 0.9f * fade); line.strokeWidth = unit * 0.008f * fade
            c.drawLine(a.x, a.y, b.x, b.y, line)
            line.color = withAlpha(col, fade); line.strokeWidth = unit * 0.014f * fade
            c.drawLine(a.x, a.y, b.x, b.y, line)
        }
    }

    private fun constellationFrame(c: Canvas, t: Float, dt: Float) {
        val link = min(w, h) * 0.2f
        val reach = min(w, h) * 0.33f
        for (i in 0 until count) {
            if (vx[i] == 0f && vy[i] == 0f) { vx[i] = (rnd.nextFloat() - 0.5f) * 40f; vy[i] = (rnd.nextFloat() - 0.5f) * 40f }
            px[i] += vx[i] * dt; py[i] += vy[i] * dt
            if (px[i] < 0 || px[i] > w) vx[i] = -vx[i]
            if (py[i] < 0 || py[i] > h) vy[i] = -vy[i]
        }
        line.shader = null
        for (i in 0 until count) for (j in i + 1 until count) {
            val d = hypot(px[i] - px[j], py[i] - py[j])
            if (d < link) {
                line.color = withAlpha(colourFor(i, t), 0.35f * (1 - d / link)); line.strokeWidth = 1.5f
                c.drawLine(px[i], py[i], px[j], py[j], line)
            }
        }
        if (touching) for (i in 0 until count) {
            val d = hypot(px[i] - tx, py[i] - ty)
            if (d < reach) {
                line.color = withAlpha(colourFor(i, t), 0.9f * (1 - d / reach)); line.strokeWidth = 2.5f
                c.drawLine(px[i], py[i], tx, ty, line)
            }
        }
        fill.shader = null
        for (i in 0 until count) {
            val tw = 0.6f + 0.4f * sin(t * 3f + ph[i])
            fill.color = withAlpha(colourFor(i, t), tw)
            c.drawCircle(px[i], py[i], min(w, h) * 0.005f, fill)
        }
    }

    private fun marblesFrame(c: Canvas, t: Float, dt: Float, gx: Float, gy: Float) {
        // Phone tilted left means the marbles roll left.
        val g = min(w, h) * 0.35f
        for (i in 0 until count) {
            vx[i] += -gx * g * dt; vy[i] += gy * g * dt
            if (touching) {
                val dx = px[i] - tx; val dy = py[i] - ty
                val d = hypot(dx, dy).coerceAtLeast(1f)
                if (d < radius[i] * 4) { vx[i] += dx / d * 1800f * dt; vy[i] += dy / d * 1800f * dt }
            }
            vx[i] *= 0.995f; vy[i] *= 0.995f
            px[i] += vx[i] * dt; py[i] += vy[i] * dt
            val r = radius[i]
            if (px[i] < r) { px[i] = r; vx[i] = -vx[i] * 0.6f }
            if (px[i] > w - r) { px[i] = w - r; vx[i] = -vx[i] * 0.6f }
            if (py[i] < r) { py[i] = r; vy[i] = -vy[i] * 0.6f }
            if (py[i] > h - r) { py[i] = h - r; vy[i] = -vy[i] * 0.6f }
        }
        // Push overlapping marbles apart and trade some speed.
        for (i in 0 until count) for (j in i + 1 until count) {
            val dx = px[j] - px[i]; val dy = py[j] - py[i]
            val d = hypot(dx, dy)
            val minD = radius[i] + radius[j]
            if (d in 0.01f..minD) {
                val nx = dx / d; val ny = dy / d
                val overlap = (minD - d) / 2
                px[i] -= nx * overlap; py[i] -= ny * overlap
                px[j] += nx * overlap; py[j] += ny * overlap
                val rel = (vx[j] - vx[i]) * nx + (vy[j] - vy[i]) * ny
                if (rel < 0) {
                    val imp = -rel * 0.85f
                    vx[i] -= nx * imp; vy[i] -= ny * imp
                    vx[j] += nx * imp; vy[j] += ny * imp
                }
            }
        }
        for (i in 0 until count) {
            val col = colourFor(i, t)
            val r = radius[i]
            fill.shader = RadialGradient(px[i] - r * 0.35f, py[i] - r * 0.35f, r * 1.3f,
                intArrayOf(Color.WHITE, col, Color.argb(255, Color.red(col) / 3, Color.green(col) / 3, Color.blue(col) / 3)),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(px[i], py[i], r, fill)
        }
        fill.shader = null
    }

    private fun tideFrame(c: Canvas, t: Float, dt: Float, gx: Float) {
        // The surface leans with the tilt, on a spring so it sloshes.
        val target = (-gx / 9.8f).coerceIn(-1f, 1f) * 0.5f
        slopeV += (target - slope) * 30f * dt
        slopeV *= 0.94f
        slope += slopeV * dt * 4f
        val n = splash.size
        for (k in 0 until 3) for (i in 0 until n) {
            val left = splash[max(0, i - 1)]; val right = splash[min(n - 1, i + 1)]
            splashV[i] += ((left + right) / 2 - splash[i]) * 0.5f - splash[i] * 0.02f
            splashV[i] *= 0.985f
        }
        for (i in 0 until n) splash[i] += splashV[i] * dt * 20f
        val level = h * (1 - battery.coerceIn(0.03f, 1f))
        val col = colourFor(0, t)
        val col2 = colourFor(1, t)
        path.reset()
        path.moveTo(0f, h.toFloat())
        for (i in 0..60) {
            val x = w * i / 60f
            val si = (i / 60f * (n - 1)).toInt()
            val y = level + slope * (x - w / 2f) + sin(x / w * 7f + t * 2f * speed) * h * 0.006f + splash[si]
            path.lineTo(x, y)
        }
        path.lineTo(w.toFloat(), h.toFloat()); path.close()
        fill.shader = LinearGradient(0f, level - h * 0.1f, 0f, h.toFloat(), col, col2, Shader.TileMode.CLAMP)
        fill.alpha = 210
        c.drawPath(path, fill)
        fill.shader = null; fill.alpha = 255
        // Bubbles.
        fill.color = withAlpha(Color.WHITE, 0.35f)
        for (i in 0 until count) {
            val p = ((t * speed * (0.08f + 0.06f * (ph[i] % 1f)) + ph[i]) % 1f)
            val x = (ph[i] * 97f % 1f) * w
            val surface = level + slope * (x - w / 2f)
            val y = h - p * (h - surface)
            c.drawCircle(x + sin(t + i) * 6f, y, min(w, h) * (0.006f + 0.008f * (ph[i] * 13f % 1f)), fill)
        }
        text.textSize = min(w, h) * 0.22f
        text.color = withAlpha(Color.WHITE, 0.16f)
        c.drawText("${(battery * 100).toInt()}%", w / 2f, max(level + h * 0.2f, h * 0.35f), text)
    }

    private fun lavaFrame(c: Canvas, t: Float, dt: Float, gx: Float, gy: Float) {
        for (i in 0 until count) {
            var ax = sin(t * 0.25f + ph[i]) * 18f - gx * 6f
            var ay = cos(t * 0.2f + ph[i] * 1.7f) * 26f + gy * 3f
            if (touching || t - lastTouchAt < 3f) {
                val dx = tx - px[i]; val dy = ty - py[i]
                val d = hypot(dx, dy).coerceAtLeast(1f)
                ax += dx / d * 70f; ay += dy / d * 70f
            }
            vx[i] = (vx[i] + ax * dt) * 0.985f; vy[i] = (vy[i] + ay * dt) * 0.985f
            px[i] += vx[i] * dt; py[i] += vy[i] * dt
            val r = radius[i]
            if (px[i] < -r * 0.3f || px[i] > w + r * 0.3f) vx[i] = -vx[i]
            if (py[i] < -r * 0.3f || py[i] > h + r * 0.3f) vy[i] = -vy[i]
            px[i] = px[i].coerceIn(-r * 0.3f, w + r * 0.3f); py[i] = py[i].coerceIn(-r * 0.3f, h + r * 0.3f)
        }
        for (i in 0 until count) {
            val col = colourFor(i, t)
            val r = radius[i] * (1f + 0.08f * sin(t * 0.8f + ph[i]))
            fill.shader = RadialGradient(px[i], py[i], r, intArrayOf(withAlpha(col, 0.95f), withAlpha(col, 0.5f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(px[i], py[i], r, fill)
        }
        fill.shader = null
    }
}
