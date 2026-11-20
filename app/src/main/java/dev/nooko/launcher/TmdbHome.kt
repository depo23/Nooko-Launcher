package dev.nooko.launcher

import dev.nooko.launcher.MainActivity.Companion.DARK_SURFACE
import dev.nooko.launcher.MainActivity.Companion.HERO_SCROLL_MS
import dev.nooko.launcher.MainActivity.Companion.HERO_TTL_MS

import dev.nooko.launcher.MainActivity.PhotoPreview
import dev.nooko.launcher.MainActivity.Prog
import dev.nooko.launcher.MainActivity.RowStyle
import dev.nooko.launcher.MainActivity.Tile
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.Dialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.content.pm.PackageInstaller
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.TextClock
import android.widget.Toast
import android.widget.VideoView
import android.view.SurfaceView
import java.io.File
import java.lang.ref.WeakReference
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject
import dev.nooko.launcher.MainActivity.App
import dev.nooko.launcher.MainActivity.Cards
import dev.nooko.launcher.MainActivity.CardSource

/**
 * The TMDB row on Home: the cards, the fullscreen billboard and the details card, as extensions of [MainActivity]. The views and the
 * state stay in MainActivity; what they do lives here.
 */
/** TMDB trending row above the apps. Needs the key from Settings > TMDB artwork; without one the row stays hidden. */
internal fun MainActivity.refreshHero() {
    val key = Tmdb.key(this)
    if (key == null) {
        heroList = emptyList()
        heroRow.removeAllViews()
        heroScroll.visibility = View.GONE
        syncHeroTitle()
        exitFull()
        return
    }
    if (heroList.isNotEmpty() && System.currentTimeMillis() - heroAt < HERO_TTL_MS) {
        if (if (heroFull()) billboard.visibility != View.VISIBLE else heroRow.childCount == 0) buildHero()
        return
    }
    Work.net.execute {
        val list = try {
            // Your own Watch Next list when it is on and signed in; this week's trending when it is off, empty or unreachable.
            val own = if (WatchNext.active(this, prefs)) try { WatchNext.heroes(this, prefs, key) } catch (e: Exception) {
                android.util.Log.w("ATV", "Watch Next failed: $e")
                emptyList()
            } else emptyList()
            own.ifEmpty { Tmdb.trending(key) }
        } catch (e: Exception) {
            android.util.Log.w("Nooko Launcher", "tmdb trending failed ($e)")
            return@execute
        }
        // The first thumbs are loaded before the row shows, so it does not fill in picture by picture.
        if (!heroFull()) for (h in list.take(4)) if (posters.get(h.backdrop) == null) try {
            decode(h.backdrop)?.let { posters.put(h.backdrop, it) }
        } catch (e: Exception) { }
        ui.post {
            if (isDestroyed) return@post
            heroList = list
            heroAt = System.currentTimeMillis()
            buildHero()
        }
    }
}

/** A small title over the cards: Watch Next when your own list is shown, else what the cards are. */
internal fun MainActivity.syncHeroTitle() {
    heroTitle.visibility = if (heroScroll.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    heroTitle.text = getString(if (WatchNext.active(this, prefs)) R.string.wn_title else R.string.tmdb_trending_title)
    ink(heroTitle, watchInk)
}

internal fun MainActivity.exitFull() {
    billboard.visibility = View.GONE
    heroBackdrop.visibility = View.GONE
    moreShade.alpha = 0f
}

internal fun MainActivity.setUpBillboard() {
    val maxW = resources.displayMetrics.widthPixels * 46 / 100
    for (t in listOf(bbTitle, bbMeta, bbGenres, bbSynopsis, bbCast)) {
        t.maxWidth = maxW
        t.setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
    }
    fun look(focused: Boolean) {
        bbButton.background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(if (focused) Color.WHITE else 0x59FFFFFF) }
        bbButton.setTextColor(if (focused) 0xFF101319.toInt() else Color.WHITE)
    }
    look(false)
    bbButton.setOnFocusChangeListener { v, focused ->
        look(focused)
        v.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(150).start()
        if (focused) lastHero = v
    }
    bbButton.setOnClickListener { heroList.getOrNull(bbIndex)?.let { openHero(it) } }
}

internal fun MainActivity.enterFull() {
    heroScroll.visibility = View.GONE
    syncHeroTitle()
    val any = heroList.isNotEmpty()
    billboard.visibility = if (any) View.VISIBLE else View.GONE
    heroBackdrop.visibility = if (any) View.VISIBLE else View.GONE
    if (!any) return
    showBillboard(bbIndex.coerceIn(0, heroList.size - 1), first = true)
    ui.removeCallbacks(heroTick)
    ui.postDelayed(heroTick, HERO_SCROLL_MS)
}

/** Left and Right on the button, or the timer: the next title, its picture crossfading and its text with it. */
internal fun MainActivity.bbStep(d: Int) {
    if (heroList.isEmpty()) return
    showBillboard((bbIndex + d + heroList.size) % heroList.size)
    ui.removeCallbacks(heroTick)
    ui.postDelayed(heroTick, HERO_SCROLL_MS)
}

/**
 * The picture, the details and the service logos are all fetched first; only then do the picture and the text change, together
 * and softly. Until they are ready the title on screen stays, so nothing jumps or shows half filled in.
 */
internal fun MainActivity.showBillboard(i: Int, first: Boolean = false) {
    bbIndex = i
    val h = heroList[i]
    val url = h.backdrop.replace("/w780/", "/w1280/")
    val id = "${h.type}${h.id}"
    val cached = heroDetails[id]
    val key = Tmdb.key(this)
    val region = Tmdb.region(prefs)
    val mine = Tmdb.mine(prefs)
    posterIo.execute {
        // Each is tried twice. The title changes only when the picture, the details and the logos have all arrived.
        var bmp = heroBg.get(url)
        repeat(2) {
            if (bmp == null) bmp = try { decode(url, resources.displayMetrics.widthPixels)?.also { heroBg.put(url, it) } } catch (e: Exception) { null }
        }
        var d = cached
        repeat(2) {
            if (d == null && key != null) d = try { Tmdb.details(h, key, region, mine) } catch (e: Exception) {
                android.util.Log.w("ATV", "tmdb details failed for ${h.type}/${h.id}: $e")
                null
            }
        }
        for (p in d?.providers.orEmpty()) repeat(2) {
            if (posters.get(p.logo) == null) try { decode(p.logo, dp(32) * 2, Bitmap.Config.ARGB_8888)?.let { posters.put(p.logo, it) } } catch (e: Exception) { }
        }
        val ready = bmp != null && (key == null || d != null) && d?.providers.orEmpty().all { posters.get(it.logo) != null }
        ui.post {
            if (isDestroyed || bbIndex != i) return@post // another title was asked for meanwhile
            if (!ready) {
                // Not complete: this title is skipped rather than shown half filled in (a few in a row, then it waits for the next turn).
                android.util.Log.w("ATV", "tmdb title ${h.type}/${h.id} skipped: not fully loaded")
                if (++bbSkips <= 3 && heroList.size > 1) showBillboard((i + 1) % heroList.size, first)
                return@post
            }
            bbSkips = 0
            if (d != null) heroDetails[id] = d
            changeBillboard(h, d, bmp, first)
        }
    }
}

internal fun MainActivity.changeBillboard(h: Tmdb.Hero, d: Tmdb.Details?, bmp: Bitmap?, first: Boolean) {
    val soft = ValueAnimator.areAnimatorsEnabled()
    if (bmp != null) {
        val into = if (bbFront) heroBgA else heroBgB
        val out = if (bbFront) heroBgB else heroBgA
        into.setImageBitmap(bmp)
        into.animate().cancel()
        out.animate().cancel()
        into.animate().alpha(1f).setDuration(if (first || !soft) 250 else 900).start()
        out.animate().alpha(0f).setDuration(if (first || !soft) 250 else 900).start()
        bbFront = !bbFront
    }
    // The text and the service logos (beside the button) change together.
    val faded = listOf<View>(bbInfo, bbProviders)
    faded.forEach { it.animate().cancel() }
    if (first || !soft) {
        fillBillboard(h, d)
        faded.forEach { it.alpha = 0f; it.animate().alpha(1f).setDuration(if (soft) 600 else 0).start() }
    } else {
        faded.forEach { it.animate().alpha(0f).setDuration(300).start() }
        bbInfo.postDelayed({
            if (isDestroyed) return@postDelayed
            fillBillboard(h, d)
            faded.forEach { it.animate().alpha(1f).setDuration(600).start() }
        }, 310)
    }
}

/** A service's logo, small and rounded, loaded into place. */
internal fun MainActivity.serviceLogo(p: Tmdb.Provider, size: Int, radiusDp: Int): ImageView {
    val icon = ImageView(this).apply {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(radiusDp).toFloat())
        }
        clipToOutline = true
        contentDescription = p.name
        setBackgroundColor(0x33FFFFFF)
    }
    posters.get(p.logo)?.let { icon.setImageBitmap(it) } ?: posterIo.execute {
        val bmp = try { decode(p.logo, size * 2, Bitmap.Config.ARGB_8888) } catch (e: Exception) { null } ?: return@execute
        posters.put(p.logo, bmp)
        ui.post { icon.setImageBitmap(bmp) }
    }
    return icon
}

internal fun MainActivity.fillBillboard(h: Tmdb.Hero, d: Tmdb.Details?) {
    bbTitle.text = h.title
    bbSynopsis.text = h.overview
    bbMeta.text = tmdbYearLine(this, h, d)
    bbGenres.text = tmdbScoreGenres(h, d)
    // The director (or creator) comes before the main actors.
    val cast = listOfNotNull(tmdbMaker(this, h, d), d?.cast?.takeIf { it.isNotEmpty() }?.let { getString(R.string.tmdb_with, it.joinToString(", ")) }).joinToString("\n")
    bbCast.text = cast
    bbCast.visibility = if (cast.isEmpty()) View.GONE else View.VISIBLE
    bbProviders.removeAllViews()
    // Only the service logos, to the right of the More info button.
    val size = dp(32)
    for (p in d?.providers.orEmpty()) {
        val icon = serviceLogo(p, size, 7)
        bbProviders.addView(icon, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) })
    }
    bbDots.text = heroList.indices.joinToString(" ") { if (it == bbIndex) "●" else "○" }
}

internal fun MainActivity.buildHero() {
    heroRow.removeAllViews()
    if (heroFull()) return enterFull()
    exitFull()
    heroScroll.visibility = if (heroList.isEmpty()) View.GONE else View.VISIBLE
    syncHeroTitle()
    // Two big cards and the edge of the third, as on Apple TV: the row invites a scroll.
    val gap = dp(24)
    val w = (resources.displayMetrics.widthPixels - dp(96) - gap * 2 - dp(36)) / 2
    val ch = w * 54 / 100
    for ((i, h) in heroList.withIndex()) {
        val frame = FrameLayout(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(if (dark) DARK_SURFACE else 0xFFDDE1E5.toInt())
        }
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)
        // Scrim and white title over the backdrop: the art is never plain, so the title can't follow the theme's ink.
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xCC000000.toInt()))
        }, FrameLayout.LayoutParams(MATCH_PARENT, ch / 2, Gravity.BOTTOM))
        frame.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(14))
            addView(TextView(context).apply {
                text = h.title
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            })
            addView(TextView(context).apply {
                text = listOfNotNull(getString(if (h.type == "movie") R.string.tmdb_movie else R.string.tmdb_series), h.scoreLabel()).joinToString(" · ")
                textSize = 14f
                setTextColor(0xCCFFFFFF.toInt())
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        // Selected: the thumb stays and a card with the details slides up under it (see showHeroCard).
        style(frame, dp(16), card = true, lift = 1.05f, frost = false) { focused ->
            if (focused) {
                lastHero = frame
                showHeroCard(h, frame)
            } else hideHeroCard(frame)
        }
        frame.setTag(R.id.heroRow, h)
        frame.contentDescription = h.title
        frame.setOnClickListener { openHero(h) }
        heroRow.addView(frame, LinearLayout.LayoutParams(w, ch).apply { if (i > 0) marginStart = gap })
        loadPoster(img, h.backdrop)
    }
    ui.removeCallbacks(heroTick)
    if (heroList.isNotEmpty()) ui.postDelayed(heroTick, HERO_SCROLL_MS)
}

internal fun MainActivity.hideHeroCard(frame: View? = null) {
    val card = heroCard ?: return
    if (frame != null && card.tag !== frame) return
    heroCard = null
    card.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(180).withEndAction {
        (card.parent as? ViewGroup)?.removeView(card)
    }.start()
}

/** Same size as the thumb, just under it: it slides up from below and fades in. The row's glide has ended by then. */
internal fun MainActivity.showHeroCard(h: Tmdb.Hero, frame: View) {
    hideHeroCard()
    frame.postDelayed({
        if (!frame.isFocused || isDestroyed) return@postDelayed
        val id = "${h.type}${h.id}"
        val key = Tmdb.key(this)
        val region = Tmdb.region(prefs)
        val mine = Tmdb.mine(prefs)
        val cached = heroDetails[id]
        // The card shows only when everything in it is ready: the details and the service logos are fetched first.
        Work.net.execute {
            var d = cached
            repeat(2) {
                if (d == null && key != null) d = try { Tmdb.details(h, key, region, mine) } catch (e: Exception) {
                    android.util.Log.w("ATV", "tmdb details failed ($e)")
                    null
                }
            }
            val size = logoSize(12f * resources.displayMetrics.scaledDensity)
            for (p in d?.providers.orEmpty()) repeat(2) {
                if (posters.get(p.logo) == null) try { decode(p.logo, size * 2, Bitmap.Config.ARGB_8888)?.let { posters.put(p.logo, it) } } catch (e: Exception) { }
            }
            ui.post {
                if (isDestroyed || !frame.isFocused) return@post // the selection moved on while it loaded
                if (d != null) heroDetails[id] = d
                slideUpHeroCard(h, frame, d)
            }
        }
    }, 230)
}

internal fun MainActivity.slideUpHeroCard(h: Tmdb.Hero, frame: View, d: Tmdb.Details?) {
    val root = findViewById<FrameLayout>(R.id.root)
    val at = IntArray(2).also { frame.getLocationInWindow(it) }
    val rootAt = IntArray(2).also { root.getLocationInWindow(it) }
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = frame
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xF2101319.toInt()) }
        elevation = dp(8).toFloat()
    }
    hideHeroCard()
    root.addView(card, FrameLayout.LayoutParams(frame.width, frame.height).apply {
        leftMargin = at[0] - rootAt[0]
        topMargin = at[1] - rootAt[1] + frame.height + dp(14)
    })
    heroCard = card
    fillHeroCard(card, h, d)
    card.alpha = 0f
    card.translationY = dp(60).toFloat()
    card.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}

internal fun MainActivity.fillHeroCard(card: LinearLayout, h: Tmdb.Hero, d: Tmdb.Details?) {
    card.removeAllViews()
    fun text(t: String, size: Float, color: Int, lines: Int, top: Int = 0, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(top), 0, 0)
    }
    card.addView(text(h.title, 20f, Color.WHITE, 1, bold = true))
    card.addView(text(tmdbYearLine(this, h, d), 12f, 0xB3FFFFFF.toInt(), 1, top = 3))
    // The service logos, each as tall as a capital letter of the genres, then the genres.
    val genres = text(tmdbGenres(d), 12f, 0xB3FFFFFF.toInt(), 1, top = 0)
    val size = logoSize(genres.textSize)
    card.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, 0)
        // The score comes first on the line, then the logos, then the genres.
        h.scoreLabel()?.let { score -> addView(text(score, 12f, 0xB3FFFFFF.toInt(), 1).apply { setPadding(0, 0, dp(6), 0) }) }
        for (p in d?.providers.orEmpty()) addView(serviceLogo(p, size, 2), LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(5) })
        addView(genres)
    })
    if (h.overview.isNotEmpty()) card.addView(text(h.overview, 13f, 0xE6FFFFFF.toInt(), 3, top = 8))
    tmdbMaker(this, h, d)?.let { card.addView(text(it, 12f, 0xB3FFFFFF.toInt(), 1, top = 6)) }
    if (d?.cast?.isNotEmpty() == true) card.addView(text(getString(R.string.tmdb_with, d.cast.joinToString(", ")), 12f, 0xB3FFFFFF.toInt(), 1, top = 3))
}

/** Every [HERO_SCROLL_MS]: the row moves one card on (back to the first after the last), but not while a card is selected. */
internal fun MainActivity.heroAdvance() {
    if (heroFull() && billboard.visibility == View.VISIBLE) {
        if (resumed && currentFocus !== bbButton && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) bbStep(1)
        return
    }
    if (!resumed || heroScroll.visibility != View.VISIBLE || heroRow.childCount <= 3) return
    if (currentFocus?.getTag(R.id.heroRow) != null || settingsScrim.visibility == View.VISIBLE || allApps.visibility == View.VISIBLE) return
    val scroll = heroScroll as HorizontalScrollView
    val step = heroRow.getChildAt(1).left - heroRow.getChildAt(0).left
    val max = maxOf(0, heroRow.width - (scroll.width - scroll.paddingLeft - scroll.paddingRight))
    val to = if (scroll.scrollX >= max - 2) 0 else minOf(scroll.scrollX + step, max)
    ValueAnimator.ofInt(scroll.scrollX, to).apply {
        duration = 500
        addUpdateListener { scroll.scrollTo(it.animatedValue as Int, 0) }
    }.start()
}

internal fun MainActivity.openHero(h: Tmdb.Hero) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/${h.type}/${h.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(h.overview.ifEmpty { getString(R.string.tmdb_hero_open_failed) }, long = true)
    }
}
