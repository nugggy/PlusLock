package io.github.nugggy.pluslock.core

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.github.nugggy.pluslock.service.PlusNotificationListener
import org.json.JSONObject
import kotlin.math.log10

/**
 * Per-app volume. Android doesn't let apps turn other apps down, but music and video apps
 * announce their audio session (as they do for equaliser apps), and an effect on that
 * session can change its loudness. Apps that don't announce one can only be paused.
 */
object AppAudio {
    /** Audio session id to the app that owns it. */
    private val sessions = HashMap<Int, String>()
    private val effects = HashMap<Int, DynamicsProcessing>()
    /** Level before muting, so unmuting puts it back. */
    private val beforeMute = HashMap<String, Int>()

    data class PlayingApp(val pkg: String, val label: String, val playing: Boolean, val controller: MediaController?, val adjustable: Boolean)

    fun levels(): Map<String, Int> {
        val o = runCatching { JSONObject(P.appVolumesJson.value) }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.optInt(it, 100) }
    }

    /** 0 to 150 percent; 100 is untouched. */
    fun level(pkg: String) = levels()[pkg] ?: 100

    fun setLevel(pkg: String, pct: Int) {
        val o = runCatching { JSONObject(P.appVolumesJson.value) }.getOrDefault(JSONObject())
        if (pct == 100) o.remove(pkg) else o.put(pkg, pct.coerceIn(0, 150))
        P.appVolumesJson.value = o.toString()
        sessions.filterValues { it == pkg }.keys.forEach { apply(it, pkg) }
        Diag.log("app volume", "$pkg set to $pct% on ${sessions.count { it.value == pkg }} session(s)")
    }

    fun toggleMute(pkg: String) {
        val now = level(pkg)
        if (now > 0) { beforeMute[pkg] = now; setLevel(pkg, 0) } else setLevel(pkg, beforeMute.remove(pkg) ?: 100)
    }

    fun resetAll() {
        P.appVolumesJson.value = "{}"
        sessions.forEach { (id, pkg) -> apply(id, pkg) }
    }

    fun adjustable(pkg: String) = sessions.containsValue(pkg)

    fun open(id: Int, pkg: String) {
        if (id <= 0 || pkg.isBlank()) return
        sessions[id] = pkg
        Diag.log("app volume", "$pkg announced audio session $id")
        apply(id, pkg)
    }

    fun close(id: Int) {
        sessions.remove(id)
        effects.remove(id)?.let { runCatching { it.release() } }
    }

    private fun apply(id: Int, pkg: String) {
        val pct = level(pkg)
        if (pct == 100) { effects.remove(id)?.let { runCatching { it.release() } }; return }
        val db = if (pct <= 0) -90f else (20 * log10(pct / 100f)).coerceIn(-60f, 6f)
        runCatching {
            val e = effects.getOrPut(id) { DynamicsProcessing(0, id, null) }
            e.setInputGainAllChannelsTo(db)
            e.enabled = true
        }.onFailure {
            effects.remove(id)?.let { e -> runCatching { e.release() } }
            Diag.log("app volume", "couldn't change $pkg: ${it.message}")
        }
    }

    /** Apps with music or video going now, newest first, playing ones before paused ones. */
    fun playing(ctx: Context): List<PlayingApp> {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val controllers = runCatching {
            msm.getActiveSessions(ComponentName(ctx, PlusNotificationListener::class.java))
        }.getOrDefault(emptyList())
        val out = LinkedHashMap<String, PlayingApp>()
        controllers.forEach { c ->
            val pkg = c.packageName
            if (pkg in out) return@forEach
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            out[pkg] = PlayingApp(pkg, Apps.label(ctx, pkg), playing, c, adjustable(pkg))
        }
        // Apps that announced audio but have no media session.
        sessions.values.distinct().filter { it !in out }.forEach { pkg ->
            out[pkg] = PlayingApp(pkg, Apps.label(ctx, pkg), true, null, true)
        }
        return out.values.sortedByDescending { it.playing }
    }
}
