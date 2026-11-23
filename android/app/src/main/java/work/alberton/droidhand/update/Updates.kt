package work.alberton.droidhand.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import work.alberton.droidhand.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * "A new version is out": the latest GitHub release of DroidHand, its APK and checksum.
 * The app checks when it opens, downloads the APK into its cache, checks the SHA-256 from
 * the release's SHA256SUMS and hands it to Android's installer (which also refuses an APK
 * signed with another key).
 */
object Updates {
    const val REPO = "gabrielalberton/droidhand"
    const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

    data class Release(val version: String, val apkUrl: String, val apkName: String, val sha256: String?, val pageUrl: String)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** True when [a] is a newer version than [b] ("1.10.0" > "1.9.2"; "v" prefix and suffixes ignored). */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The latest release when it is newer than this app, else null. Network errors also give null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            val json = http.newCall(req).execute().use { r -> if (!r.isSuccessful) return@runCatching null; JSONObject(r.body!!.string()) }
            val version = json.optString("tag_name").removePrefix("v")
            if (version.isBlank() || !isNewer(version, BuildConfig.VERSION_NAME)) return@runCatching null
            val assets = json.optJSONArray("assets") ?: return@runCatching null
            val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            val apk = list.firstOrNull { it.optString("name") == "droidhand-$version.apk" }
                ?: list.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@runCatching null
            val sums = list.firstOrNull { it.optString("name") == "SHA256SUMS" }?.optString("browser_download_url")
            val sha = sums?.let { url ->
                runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.string() }
                        ?.lineSequence()?.map { it.trim().split(Regex("\\s+")) }
                        ?.firstOrNull { it.size >= 2 && it[1].trimStart('*') == apk.optString("name") }?.get(0)?.lowercase()
                }.getOrNull()
            }
            Release(version, apk.optString("browser_download_url"), apk.optString("name"), sha, json.optString("html_url", RELEASES_URL))
        }.getOrNull()
    }

    /** Download the APK into the app cache and check its SHA-256; [progress] gets 0..1. */
    suspend fun download(ctx: Context, rel: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, rel.apkName)
        val md = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val body = r.body ?: error("empty download")
            val total = body.contentLength().takeIf { it > 0 }
            require(total == null || total < 64L * 1024 * 1024) { "APK too large" }
            body.byteStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        total?.let { progress(done.toFloat() / it) }
                    }
                }
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (rel.sha256 != null && rel.sha256 != got) { out.delete(); error("checksum mismatch") }
        out
    }

    /** Android may first ask the user to let DroidHand install apps; true when it can install now. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
