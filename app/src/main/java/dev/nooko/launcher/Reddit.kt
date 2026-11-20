package dev.nooko.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A wallpaper from the week's top pictures of the subreddits you choose, one new picture a day. It follows [Bing]:
 * one JPEG on disk, a lookup at most once a day while the home screen is open, no service. Reddit's public feed (RSS)
 * is used, with no account. If Reddit stops answering or limits the requests, the last picture stays.
 */
internal object Reddit {
    const val CHOICE = "reddit"

    /** Subreddit to the string that says in a few words what it shows. All of them are picture subreddits that are safe for work. */
    val SUGGESTED = linkedMapOf(
        "TVWallpapersSFW" to R.string.reddit_about_tvwallpapers,
        "EarthPorn" to R.string.reddit_about_earthporn,
        "LakePorn" to R.string.reddit_about_lakeporn,
        "MountainPorn" to R.string.reddit_about_mountainporn,
        "ForestPorn" to R.string.reddit_about_forestporn,
        "DesertPorn" to R.string.reddit_about_desertporn,
        "AutumnPorn" to R.string.reddit_about_autumnporn,
        "WaterPorn" to R.string.reddit_about_waterporn,
        "SkyPorn" to R.string.reddit_about_skyporn,
        "spaceporn" to R.string.reddit_about_spaceporn,
        "astrophotography" to R.string.reddit_about_astrophotography,
        "ImaginaryLandscapes" to R.string.reddit_about_imaginarylandscapes,
        "CityPorn" to R.string.reddit_about_cityporn,
        "wallpaper" to R.string.reddit_about_wallpaper,
        "wallpapers" to R.string.reddit_about_wallpapers,
        "WidescreenWallpaper" to R.string.reddit_about_widescreenwallpaper,
        "WQHD_Wallpaper" to R.string.reddit_about_wqhd_wallpaper,
        "MinimalWallpaper" to R.string.reddit_about_minimalwallpaper,
        "Amoledbackgrounds" to R.string.reddit_about_amoledbackgrounds,
    )
    private val DEFAULT = setOf("TVWallpapersSFW")
    private const val AGENT = "android:dev.nooko.launcher:v1.0 (Android TV home screen; wallpaper from the public feed)"
    private val image = Regex("https://i\\.redd\\.it/[A-Za-z0-9_-]+\\.(?:jpe?g|png)")

    @Volatile
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val inFlight = AtomicBoolean(false)
    private val lock = Any()

    fun file(c: Context) = File(c.filesDir, "reddit.jpg")

    fun subs(prefs: SharedPreferences): Set<String> = prefs.getStringSet("reddit_subs", DEFAULT)!!.filter { it in SUGGESTED }.toSet().ifEmpty { DEFAULT }

    private fun used(prefs: SharedPreferences) = prefs.getString("wp_light", null) == CHOICE || prefs.getString("wp_dark", null) == CHOICE

    /** A new picture if a wallpaper uses it and today's has not come yet: a minute apart until there is one, an hour apart after. [force] now. */
    fun refresh(c: Context, prefs: SharedPreferences, force: Boolean = false) {
        if (!used(prefs)) return
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val retry = if (file(c).exists()) 3_600_000L else 60_000L
        if (!force && (prefs.getLong("reddit_day", 0) == today || now - prefs.getLong("reddit_try", 0) < retry)) return
        prefs.edit().putLong("reddit_try", now).apply()
        if (!inFlight.compareAndSet(false, true)) return
        worker.execute {
            try {
                val token = download(c, prefs.getString("reddit_last", null), subs(prefs).toList().shuffled())
                synchronized(lock) {
                    if (!used(prefs)) file(c).delete()
                    else if (token != null) {
                        prefs.edit().putLong("reddit_day", today).putString("reddit_last", token).apply()
                        main.post { onChanged?.invoke() }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Nooko Launcher", "Reddit picture failed ($e)") // logged only: the last picture stays
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** No wallpaper uses it any more: the picture and what is remembered about it go. */
    fun dropIfUnused(c: Context, prefs: SharedPreferences) = synchronized(lock) {
        if (used(prefs)) return@synchronized
        file(c).delete()
        prefs.edit().remove("reddit_last").remove("reddit_day").remove("reddit_try").apply()
        if (!inFlight.get()) File(c.filesDir, "reddit.part").delete()
    }

    /** Returns the new picture's address, or null when no picture fits. Throws when the network or Reddit fails. */
    private fun download(c: Context, last: String?, subs: List<String>): String? {
        for (sub in subs) {
            // One subreddit that does not answer (gone, limited) is skipped: the next one is tried.
            val feed = feed(sub) ?: continue
            val candidates = image.findAll(feed).map { it.value }.distinct().filter { it != last }.take(40).toList().shuffled().take(5)
            android.util.Log.i("Nooko Launcher", "Reddit r/$sub: ${candidates.size} pictures to try")
            for (url in candidates) {
                val part = File(c.filesDir, "reddit.part")
                try {
                    val conn = connect(url)
                    if (conn.contentLengthLong > 15_000_000L) continue // too big for a TV to be worth it
                    conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(part.path, bounds)
                    // A TV wallpaper is wide and at least Full HD: no portraits, no small pictures.
                    if (bounds.outWidth < 1920 || bounds.outWidth < bounds.outHeight * 1.4) continue
                    if (!part.renameTo(file(c))) throw IOException("not saved")
                    return url
                } finally {
                    part.delete()
                }
            }
        }
        return null
    }

    private const val BROWSER = "Mozilla/5.0 (Linux; Android 11; AFTMA08C15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * The week's top posts of a subreddit. Reddit refuses some requests (429, or a fake 404) depending on how they look, so
     * several addresses and ways of asking are tried in turn; the first answer that holds pictures wins. Each failure is logged.
     */
    private fun feed(sub: String): String? {
        val tries = listOf(
            "https://www.reddit.com/r/$sub/top/.rss?t=week" to AGENT,
            "https://old.reddit.com/r/$sub/top/.rss?t=week" to AGENT,
            "https://www.reddit.com/r/$sub/top/.rss?t=week" to BROWSER,
            "https://old.reddit.com/r/$sub/top.json?t=week&limit=50&raw_json=1" to BROWSER,
            "https://www.reddit.com/r/$sub/top.json?t=week&limit=50&raw_json=1" to BROWSER,
        )
        for ((url, agent) in tries) {
            try {
                val conn = Http.open(url, mapOf("User-Agent" to agent, "Accept" to "application/rss+xml, application/json, text/xml, */*"))
                val code = conn.responseCode
                if (code !in 200..299) { android.util.Log.w("Nooko Launcher", "Reddit $code for $url"); continue }
                val body = conn.inputStream.use { it.readBytes().decodeToString() }
                if (image.containsMatchIn(body)) return body
                android.util.Log.w("Nooko Launcher", "Reddit $code, no pictures, ${body.length} bytes, for $url: ${body.take(160).replace('\n', ' ')}")
            } catch (e: IOException) {
                android.util.Log.w("Nooko Launcher", "Reddit failed for $url ($e)")
            }
        }
        return null
    }

    private fun connect(url: String) = Http.open(url, mapOf("User-Agent" to AGENT))
}
