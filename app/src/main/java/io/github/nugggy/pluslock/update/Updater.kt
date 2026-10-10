package io.github.nugggy.pluslock.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import io.github.nugggy.pluslock.BuildConfig
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Versions
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Release(
    val tag: String,
    val title: String,
    val notes: String,
    val prerelease: Boolean,
    val apkUrl: String,
    val apkSize: Long,
    val shaUrl: String?,
    val code: Long,
    val pageUrl: String,
)

/**
 * Updates come from GitHub Releases on [BuildConfig.UPDATE_REPO].
 * Stable channel: full releases only. Beta channel: pre-releases too.
 * Every download is checked against the release checksum and must be signed
 * with the same key as the installed app before it is handed to Android.
 */
object Updater {
    private const val API = "https://api.github.com/repos/"
    val repoUrl = "https://github.com/" + BuildConfig.UPDATE_REPO

    val currentCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    fun fetchLatest(channel: String = P.updateChannel.value): Release? {
        val body = get("$API${BuildConfig.UPDATE_REPO}/releases?per_page=20") ?: return null
        val arr = JSONArray(body)
        val releases = mutableListOf<Release>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val pre = r.optBoolean("prerelease")
            if (pre && channel != "beta") continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: Pair<String, Long>? = null
            var sha: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith(".apk")) apk = url to a.optLong("size")
                if (name.endsWith(".apk.sha256")) sha = url
            }
            val (apkUrl, size) = apk ?: continue
            val tag = r.optString("tag_name")
            releases += Release(
                tag = tag,
                title = r.optString("name").ifBlank { tag },
                notes = r.optString("body"),
                prerelease = pre,
                apkUrl = apkUrl,
                apkSize = size,
                shaUrl = sha,
                code = Versions.code(tag),
                pageUrl = r.optString("html_url"),
            )
        }
        return releases.maxByOrNull { it.code }
    }

    fun isNewer(r: Release) = r.code > currentCode

    fun download(ctx: Context, r: Release, progress: (Float) -> Unit = {}): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "PlusLock-${r.tag}.apk")
        val conn = open(r.apkUrl)
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        r.shaUrl?.let { url ->
            val expected = get(url)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
            val actual = sha256(out)
            if (expected == null || expected != actual) {
                out.delete()
                throw SecurityException("Checksum mismatch, update discarded")
            }
        }
        verifyPackage(ctx, out)
        return out
    }

    private fun verifyPackage(ctx: Context, apk: File) {
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw SecurityException("Downloaded file is not a valid app")
        if (archive.packageName != ctx.packageName) {
            apk.delete()
            throw SecurityException("Update is for a different app (${archive.packageName})")
        }
        if (archive.longVersionCode <= currentCode) {
            apk.delete()
            throw SecurityException("Update is not newer than the installed version")
        }
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        if (mine.isEmpty() || mine != theirs) {
            apk.delete()
            throw SecurityException("Update is signed with a different key")
        }
    }

    /**
     * Hands the APK to Android. Once PlusLock has installed itself once, Android
     * treats it as its own installer and later updates need no tap.
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            val pi = PendingIntent.getBroadcast(
                ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        }
    }

    /** Called by the background job. Returns a short status line. */
    fun runScheduled(ctx: Context, foregroundUi: Boolean): String {
        P.updateLastCheck.value = System.currentTimeMillis()
        val r = runCatching { fetchLatest() }.getOrElse { return status("Couldn't reach GitHub: ${it.message}") }
            ?: return status("No releases published yet")
        if (!isNewer(r)) return status("Up to date")
        if (!P.autoUpdate.value || BuildConfig.DEBUG) {
            Notifs.post(ctx, Notifs.ID_UPDATE, Notifs.CH_UPDATES, "PlusLock ${r.tag} is available",
                "Open PlusLock to install it.", Notifs.openApp(ctx, "updates"))
            return status("${r.tag} available")
        }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Notifs.post(ctx, Notifs.ID_UPDATE, Notifs.CH_UPDATES, "PlusLock ${r.tag} is ready",
                "Allow PlusLock to install updates so it can apply them for you.", Notifs.openApp(ctx, "updates"))
            return status("${r.tag} available, install permission needed")
        }
        if (foregroundUi) return status("${r.tag} available")
        return runCatching {
            val apk = download(ctx, r)
            install(ctx, apk)
            status("Installing ${r.tag}")
        }.getOrElse { status("Update failed: ${it.message}") }
    }

    private fun status(s: String): String {
        P.updateStatus.value = s
        return s
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "PlusLock/${BuildConfig.VERSION_NAME}")
        if (url.startsWith(API)) c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return c
    }

    private fun get(url: String): String? = runCatching {
        open(url).inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { if (it is IllegalStateException && it.message == "HTTP 404") null else throw it }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
