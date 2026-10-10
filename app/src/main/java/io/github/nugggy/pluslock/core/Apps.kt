package io.github.nugggy.pluslock.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(val pkg: String, val label: String)

object Apps {
    private val labels = ConcurrentHashMap<String, String>()
    private val accents = ConcurrentHashMap<String, Int>()
    private val icons = object : android.util.LruCache<String, Bitmap>(160) {}

    fun launchable(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .onEach { labels[it.pkg] = it.label }
    }

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        val pm = ctx.packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    fun icon(ctx: Context, pkg: String): Drawable? =
        runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    fun iconBitmap(ctx: Context, pkg: String, sizePx: Int = 96): Bitmap? {
        icons.get(pkg)?.let { return it }
        val d = icon(ctx, pkg) ?: return null
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(c)
        icons.put(pkg, bmp)
        return bmp
    }

    /** A vivid colour pulled from the app icon, used when the app gives no brand colour. */
    fun accentColour(ctx: Context, pkg: String): Int = accents.getOrPut(pkg) {
        val bmp = iconBitmap(ctx, pkg) ?: return@getOrPut 0xFF7FB2FF.toInt()
        val buckets = DoubleArray(24)
        val sums = Array(24) { DoubleArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until bmp.height step 3) for (x in 0 until bmp.width step 3) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) < 160) continue
            Color.colorToHSV(p, hsv)
            val score = (hsv[1] * hsv[2]).toDouble()
            if (score < 0.15) continue
            val b = ((hsv[0] / 15f).toInt()).coerceIn(0, 23)
            buckets[b] += score
            sums[b][0] += Color.red(p) * score
            sums[b][1] += Color.green(p) * score
            sums[b][2] += Color.blue(p) * score
        }
        val best = buckets.indices.maxByOrNull { buckets[it] } ?: 0
        if (buckets[best] == 0.0) return@getOrPut 0xFFE0E0E0.toInt()
        val w = buckets[best]
        val c = Color.rgb((sums[best][0] / w).toInt(), (sums[best][1] / w).toInt(), (sums[best][2] / w).toInt())
        vivid(c)
    }

    /** Push a colour toward full brightness so it glows on a dark screen. */
    fun vivid(colour: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(colour, hsv)
        if (hsv[1] < 0.12f) return Color.WHITE
        hsv[1] = hsv[1].coerceAtLeast(0.55f)
        hsv[2] = hsv[2].coerceAtLeast(0.9f)
        return Color.HSVToColor(hsv)
    }
}
