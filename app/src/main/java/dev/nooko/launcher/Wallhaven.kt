package dev.nooko.launcher

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A wallpaper from the year's best pictures of the topics you choose, on wallhaven.cc: one new random picture a day, safe for work only,
 * wide and at least Full HD. It follows [Bing]: one JPEG on disk, a lookup at most once a day while the home screen is open, no service.
 * Wallhaven's public search needs no account or key. If it stops answering, the last picture stays.
 */
internal object Wallhaven {
    const val CHOICE = "wallhaven"

    /** Topic (also its search word) to its name and a line about it. */
    val TOPICS = linkedMapOf(
        "nature" to (R.string.wh_nature to R.string.wh_nature_about),
        "landscape" to (R.string.wh_landscape to R.string.wh_landscape_about),
        "mountains" to (R.string.wh_mountains to R.string.wh_mountains_about),
        "ocean" to (R.string.wh_ocean to R.string.wh_ocean_about),
        "sunset" to (R.string.wh_sunset to R.string.wh_sunset_about),
        "space" to (R.string.wh_space to R.string.wh_space_about),
        "abstract" to (R.string.wh_abstract to R.string.wh_abstract_about),
        "city" to (R.string.wh_city to R.string.wh_city_about),
        "minimalism" to (R.string.wh_minimal to R.string.wh_minimal_about),
        "fantasy" to (R.string.wh_fantasy to R.string.wh_fantasy_about),
    )
    private val DEFAULT = setOf("nature", "landscape", "space")
    private const val AGENT = "android:dev.nooko.launcher:v1.0 (Android TV home screen)"

    @Volatile
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue())
    private val inFlight = AtomicBoolean(false)
    private val lock = Any()

    fun file(c: Context) = File(c.filesDir, "wallhaven.jpg")

    fun topics(prefs: SharedPreferences): Set<String> = prefs.getStringSet("wh_topics", DEFAULT)!!.filter { it in TOPICS }.toSet().ifEmpty { DEFAULT }

    private fun used(prefs: SharedPreferences) = prefs.getString("wp_light", null) == CHOICE || prefs.getString("wp_dark", null) == CHOICE

    /** A new picture if a wallpaper uses it and today's has not come yet: a minute apart until there is one, an hour apart after. [force] now. */
    fun refresh(c: Context, prefs: SharedPreferences, force: Boolean = false) {
        if (!used(prefs)) return
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val retry = if (file(c).exists()) 3_600_000L else 60_000L
        if (!force && (prefs.getLong("wh_day", 0) == today || now - prefs.getLong("wh_try", 0) < retry)) return
        prefs.edit().putLong("wh_try", now).apply()
        if (!inFlight.compareAndSet(false, true)) return
        worker.execute {
            try {
                val token = download(c, prefs.getString("wh_last", null), topics(prefs).toList().shuffled())
                synchronized(lock) {
                    if (!used(prefs)) file(c).delete()
                    else if (token != null) {
                        prefs.edit().putLong("wh_day", today).putString("wh_last", token).apply()
                        main.post { onChanged?.invoke() }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("Nooko Launcher", "Wallhaven picture failed ($e)") // logged only: the last picture stays
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** No wallpaper uses it any more: the picture and what is remembered about it go. */
    fun dropIfUnused(c: Context, prefs: SharedPreferences) = synchronized(lock) {
        if (used(prefs)) return@synchronized
        file(c).delete()
        prefs.edit().remove("wh_last").remove("wh_day").remove("wh_try").apply()
        if (!inFlight.get()) File(c.filesDir, "wallhaven.part").delete()
    }

    /** The full-size pictures of one page of a topic's best of the year: address and size in bytes. Throws when the network fails. */
    internal fun parse(json: String): List<Pair<String, Long>> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.getJSONObject(i)
            val path = o.optString("path")
            if (path.startsWith("https://w.wallhaven.cc/") && o.optString("purity") == "sfw") path to o.optLong("file_size") else null
        }
    }

    private fun page(topic: String, page: Int): List<Pair<String, Long>> {
        val url = "https://wallhaven.cc/api/v1/search?q=$topic&categories=100&purity=100&atleast=1920x1080&ratios=16x9&sorting=toplist&topRange=1y&page=$page"
        val r = Http.send(url, headers = mapOf("User-Agent" to AGENT), connectMs = 8000, readMs = 12000)
        if (!r.ok) { android.util.Log.w("Nooko Launcher", "Wallhaven ${r.code} for $topic page $page"); return emptyList() }
        return parse(r.body)
    }

    /** Returns the new picture's address, or null when no picture fits. Throws when the network fails. */
    private fun download(c: Context, last: String?, topics: List<String>): String? {
        for (topic in topics) {
            // A random page of the first five: a different picture each day, all of them among the best.
            val found = page(topic, (1..5).random()).ifEmpty { page(topic, 1) }
            val candidates = found.filter { it.first != last && it.second in 1..15_000_000L }.shuffled().take(4)
            android.util.Log.i("Nooko Launcher", "Wallhaven $topic: ${candidates.size} pictures to try")
            for ((url, _) in candidates) {
                val part = File(c.filesDir, "wallhaven.part")
                try {
                    val conn = Http.open(url, mapOf("User-Agent" to AGENT))
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
}
