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
 * A wallpaper from the month's top pictures of r/TVWallpapersSFW, one new random picture a day. It follows [Bing]:
 * one JPEG on disk, a lookup at most once a day while the home screen is open, no service. Reddit's public feed (RSS)
 * is used, with no account. If Reddit stops answering or limits the requests, the last picture stays.
 */
internal object Reddit {
    const val CHOICE = "reddit"

    /** The one subreddit it draws from: TV wallpapers, all safe for work. */
    private const val SUB = "TVWallpapersSFW"
    private const val AGENT = "Nooko-Launcher/1.0 (Android TV home screen; wallpaper from Reddit's public feed)"
    private val image = Regex("https://i\\.redd\\.it/[A-Za-z0-9]+\\.(?:jpe?g|png)")

    @Volatile
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val inFlight = AtomicBoolean(false)
    private val lock = Any()

    fun file(c: Context) = File(c.filesDir, "reddit.jpg")

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
                val token = download(c, prefs.getString("reddit_last", null))
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
    private fun download(c: Context, last: String?): String? {
        run {
            val feed = connect("https://www.reddit.com/r/$SUB/top/.rss?t=month&limit=100").inputStream.use { it.readBytes().decodeToString() }
            val candidates = image.findAll(feed).map { it.value }.distinct().filter { it != last }.toList().shuffled().take(6)
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

    private fun connect(url: String) = Http.open(url, mapOf("User-Agent" to AGENT))
}
