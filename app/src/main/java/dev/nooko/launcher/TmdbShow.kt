package dev.nooko.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.net.URL
import java.util.concurrent.Executors

internal const val TMDB_SCREENSAVER = "tmdb"

/**
 * The screensaver of this week's trending titles: one fullscreen backdrop at a time with the title, synopsis, actors and
 * streaming services, and the clock. No apps, no settings, no icons. The picture and text crossfade every 15 seconds.
 */
internal class TmdbShow(private val context: Context) {
    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val key = Tmdb.key(context)
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private var list = emptyList<Tmdb.Hero>()
    private var index = 0
    private var front = false // true while the second picture is the one showing
    private var released = false

    private val bgA = picture()
    private val bgB = picture()
    private val title = text(34f, Color.WHITE, medium = true, lines = 2)
    private val meta = text(15f, 0xD9FFFFFF.toInt(), lines = 1)
    private val genres = text(15f, 0xD9FFFFFF.toInt(), lines = 1)
    private val synopsis = text(17f, Color.WHITE, lines = 3)
    private val cast = text(15f, 0xD9FFFFFF.toInt(), lines = 2)
    private val services = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val genreRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; addView(services); addView(genres) }
    private val info = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(title)
        addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(6) })
        addView(genreRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(2) })
        addView(synopsis, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(10) })
        addView(cast, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(10) })
    }
    val view = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(bgA, MATCH_PARENT, MATCH_PARENT)
        addView(bgB, MATCH_PARENT, MATCH_PARENT)
        addView(View(context).apply { setBackgroundResource(R.drawable.hero_shade) }, MATCH_PARENT, MATCH_PARENT)
        addView(info, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = px(48)
            bottomMargin = px(56)
        })
        addView(TextClock(context).apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(px(4).toFloat(), 0f, px(1).toFloat(), 0x99000000.toInt())
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = px(32)
            rightMargin = px(48)
        })
    }
    private val tick = object : Runnable {
        override fun run() {
            step(1)
        }
    }

    init {
        info.alpha = 0f
        val max = context.resources.displayMetrics.widthPixels * 46 / 100
        for (t in listOf(title, meta, genres, synopsis, cast)) t.maxWidth = max
        if (key != null) io.execute {
            val trending = try { Tmdb.trending(key).shuffled() } catch (e: Exception) { emptyList() }
            main.post { if (!released && trending.isNotEmpty()) { list = trending; show(0) } }
        }
    }

    fun step(by: Int) {
        if (list.isEmpty()) return
        show((index + by + list.size) % list.size)
    }

    fun release() {
        released = true
        main.removeCallbacks(tick)
        io.shutdownNow()
    }

    private fun show(i: Int) {
        index = i
        main.removeCallbacks(tick)
        main.postDelayed(tick, 15_000L)
        val h = list[i]
        val first = bgA.drawable == null && bgB.drawable == null
        // Picture and text come in together, so a title never shows over another one's picture.
        io.execute {
            val bmp = try { fetch(h.backdrop.replace("/w780/", "/w1280/")) } catch (e: Exception) { null }
            val details = try { key?.let { Tmdb.details(h, it, Tmdb.region(prefs), Tmdb.mine(prefs)) } } catch (e: Exception) { null }
            val logos = details?.providers.orEmpty().map { p -> p to (try { fetch(p.logo) } catch (e: Exception) { null }) }
            main.post { if (!released && index == i) swap(h, details, bmp, logos, first) }
        }
    }

    private fun swap(h: Tmdb.Hero, d: Tmdb.Details?, bmp: Bitmap?, logos: List<Pair<Tmdb.Provider, Bitmap?>>, first: Boolean) {
        val into = if (front) bgA else bgB
        val out = if (front) bgB else bgA
        if (bmp != null) {
            into.setImageBitmap(bmp)
            into.animate().cancel()
            out.animate().cancel()
            into.animate().alpha(1f).setDuration(if (first) 400 else 900).start()
            out.animate().alpha(0f).setDuration(if (first) 400 else 900).start()
            front = !front
        }
        info.animate().cancel()
        info.animate().alpha(0f).setDuration(if (first) 0 else 250).withEndAction {
            if (released) return@withEndAction
            title.text = h.title
            meta.text = tmdbMetaLine(context, h, d)
            genres.text = tmdbGenres(d)
            synopsis.text = h.overview
            val people = d?.cast?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.tmdb_with, it.joinToString(", ")) }.orEmpty()
            val credits = listOfNotNull(tmdbMaker(context, h, d), people.ifEmpty { null }).joinToString("\n")
            cast.text = credits
            cast.visibility = if (credits.isEmpty()) View.GONE else View.VISIBLE
            services.removeAllViews()
            for ((p, logo) in logos) services.addView(ImageView(context).apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, px(2).toFloat())
                }
                clipToOutline = true
                contentDescription = p.name
                alpha = if (p.mine || logos.none { it.first.mine }) 1f else 0.4f
                setImageBitmap(logo)
            }, LinearLayout.LayoutParams(logoSize(genres.textSize), logoSize(genres.textSize)).apply { rightMargin = px(6) })
            info.animate().alpha(1f).setDuration(500).start()
        }.start()
    }

    private fun fetch(url: String): Bitmap? = ImageCache.open(context, url, readMs = 12000).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) }

    private fun picture() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun text(size: Float, color: Int, medium: Boolean = false, lines: Int = 1) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(px(4).toFloat(), 0f, px(1).toFloat(), 0x99000000.toInt())
    }
}

/** "Movie · 2h 15min" or "TV series · 3 seasons · 24 episodes": what the title is and how long it is. */
internal fun tmdbKind(c: Context, h: Tmdb.Hero, d: Tmdb.Details?): String {
    val kind = c.getString(if (h.type == "movie") R.string.tmdb_movie else R.string.tmdb_series)
    val length = if (h.type == "movie") d?.runtime?.takeIf { it > 0 }?.let {
        if (it >= 60) c.getString(R.string.tmdb_hours_min, it / 60, it % 60) else c.getString(R.string.tmdb_min, it)
    } else listOfNotNull(
        d?.seasons?.takeIf { it > 0 }?.let { c.resources.getQuantityString(R.plurals.tmdb_seasons, it, it) },
        d?.episodes?.takeIf { it > 0 }?.let { c.resources.getQuantityString(R.plurals.tmdb_episodes, it, it) },
    ).joinToString(" · ").ifEmpty { null }
    return listOfNotNull(kind, length).joinToString(" · ")
}

/** "Directed by X" for a film, "Created by X" for a series; nothing when TMDB has no name. */
internal fun tmdbMaker(c: Context, h: Tmdb.Hero, d: Tmdb.Details?): String? =
    d?.director?.takeIf { it.isNotEmpty() }?.let { c.getString(if (h.type == "movie") R.string.tmdb_directed else R.string.tmdb_created, it) }

/** The first line of the details: score, year, kind and length. The service logos and the genres make the second line. */
internal fun tmdbMetaLine(c: Context, h: Tmdb.Hero, d: Tmdb.Details?): String =
    listOfNotNull(h.scoreLabel(), d?.year?.takeIf { it.isNotEmpty() }, tmdbKind(c, h, d)).joinToString(" · ")

internal fun tmdbGenres(d: Tmdb.Details?): String = d?.genres?.joinToString(", ").orEmpty()

/** A service logo as small as a capital letter of the genre text next to it. */
internal fun logoSize(textSizePx: Float): Int = (textSizePx * 0.78f).toInt().coerceAtLeast(8)

/** Fullscreen and cards, first line: year, kind and length. (The screensaver keeps its own lines.) */
internal fun tmdbYearLine(c: Context, h: Tmdb.Hero, d: Tmdb.Details?): String =
    listOfNotNull(d?.year?.takeIf { it.isNotEmpty() }, tmdbKind(c, h, d)).joinToString(" · ")

/** Fullscreen, second line: the score, then the genres. */
internal fun tmdbScoreGenres(h: Tmdb.Hero, d: Tmdb.Details?): String =
    listOfNotNull(h.scoreLabel(), tmdbGenres(d).ifEmpty { null }).joinToString(" · ")
