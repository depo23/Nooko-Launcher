package dev.nooko.launcher

import dev.nooko.launcher.MainActivity.Companion.APPS
import dev.nooko.launcher.MainActivity.Companion.BANNER_DIM
import dev.nooko.launcher.MainActivity.Companion.BRAND_COLOURS
import dev.nooko.launcher.MainActivity.Companion.CVTE_SOURCE_PANEL
import dev.nooko.launcher.MainActivity.Companion.DEFAULT_ORDER
import dev.nooko.launcher.MainActivity.Companion.EASE
import dev.nooko.launcher.MainActivity.Companion.GREYSCALE
import dev.nooko.launcher.MainActivity.Companion.GRID_GAP
import dev.nooko.launcher.MainActivity.Companion.HDMI
import dev.nooko.launcher.MainActivity.Companion.LOGOS
import dev.nooko.launcher.MainActivity.Companion.ROW_TILES
import dev.nooko.launcher.MainActivity.Companion.SOURCES

import dev.nooko.launcher.MainActivity.PhotoPreview
import dev.nooko.launcher.MainActivity.Prog
import dev.nooko.launcher.MainActivity.RowStyle
import dev.nooko.launcher.MainActivity.Tile
import dev.nooko.launcher.MainActivity.App
import dev.nooko.launcher.MainActivity.Cards
import dev.nooko.launcher.MainActivity.CardSource
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

/**
 * The apps on Home: the favourites shelf, the grid under it, the All apps and recent apps screens and the hold menus, as extensions
 * of [MainActivity]. The views and the state stay in MainActivity; what they do lives here.
 */
/** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
internal fun MainActivity.refreshApps() {
    appsDirty = false
    io.execute {
        val list = try {
            loadApps()
        } catch (e: Exception) {
            return@execute
        }
        val inputs = hdmiInputs()
        val panel = Intent("com.cvte.intent.SHOW_INPUT").setPackage("com.cvte.tv.setting").takeIf {
            it.resolveActivityInfo(packageManager, PackageManager.MATCH_DEFAULT_ONLY)?.exported == true
        }
        ui.post {
            if (isDestroyed) return@post
            if (inputs.isNotEmpty() && !prefs.getBoolean("inputs_moved", false)) moveInputsToSources(inputs)
            apps = list
            APPS = list
            sources = inputs
            SOURCES = inputs
            cvteSourcePanel = panel
            CVTE_SOURCE_PANEL = panel
            if (!topEditing) applyTopBar()
            renderApps()
            if (allApps.visibility == View.VISIBLE) openAllApps(recentAppsOpen) // a fresh remote request can precede the app list
        }
    }
}

/** The apps on the shelf: in your order, or with the ones you open most first (inputs keep their place at the front). */
internal fun MainActivity.favoriteApps(hidden: Set<String>): List<App> {
    val list = sortedApps().filter { inRow(it.pkg, hidden) }
    if (!favSortUsage()) return list
    val (inputs, rest) = list.partition { it.pkg.startsWith(HDMI) }
    return inputs + rest.sortedByDescending { prefs.getInt("n_" + it.pkg, 0) }
}

internal fun MainActivity.sortedApps(): List<App> {
    // Streaming apps first by default, then alphabetical; manual order or usage overrides.
    val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .thenBy { it.label.lowercase() }
    if (prefs.getString("sort", "manual") == "usage") {
        return listed().sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
    }
    val order = prefs.getString("order", "")!!.split(',')
    // An input just pinned goes first, so it's visible without scrolling.
    return listed().sortedWith(compareBy<App> {
        order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
    }.then(byDefault))
}

internal fun MainActivity.renderApps() {
    // First run with the grid: five favourites on the shelf, every other app in the grid under it.
    if (!prefs.getBoolean("grid_v1", false) && apps.isNotEmpty()) {
        val keep = sortedApps().filter { !it.pkg.startsWith(HDMI) }.take(5).map { it.pkg }.toSet()
        prefs.edit().putStringSet("row_hidden", apps.map { it.pkg }.filter { it !in keep && !it.startsWith(HDMI) }.toSet())
            .putBoolean("grid_v1", true).apply()
    }
    renderMoreApps()
    val focusedTag = currentFocus?.tag
    // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
    // First fill after a cold start: Android already gave the default focus to the top bar's first icon.
    val firstFill = appRow.childCount == 0 && currentFocus?.parent === wifi.parent
    val takeFocus = currentFocus == null || currentFocus?.parent === appRow || firstFill
    val gap = dp(GRID_GAP)
    val width = rowTileWidth()
    val tileH = if (isBannerStyle(iconStyle())) width * 9 / 16 else (dp(76) * rowScale()).toInt() // 16:9 like a banner
    // The row sits on the bottom of its space. A banner row is shorter, so lift it by half the difference: its
    // centre stays where the card row's is, between the clock and Continue Watching. A taller row (Large,
    // Largest) grows upward only, or it would cover Continue Watching's title.
    findViewById<View>(R.id.appScroll).apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(-24) + maxOf(0, dp(76) - tileH) / 2
        requestLayout()
    }
    // Apple TV size: the row sits on a rounded glass shelf.
    findViewById<View>(R.id.appScroll).background = if (rowSize() == "tv") GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(if (dark) 0x14FFFFFF else 0x66FFFFFF)
    } else null
    appRow.removeAllViews()
    val hidden = rowHiddenSet()
    val tiles = favoriteApps(hidden).map { appTile(it, labeled = false) }
    if (tiles.isEmpty()) return showNames()
    tiles.forEachIndexed { i, t ->
        t.id = View.generateViewId()
        appRow.addView(t, LinearLayout.LayoutParams(width, tileH).apply { if (i > 0) marginStart = gap })
        val own = t.onFocusChangeListener
        t.setOnFocusChangeListener { v, focused ->
            own.onFocusChange(v, focused)
            if (focused) lastApp = v
            showRowLabel(v, focused)
        }
    }
    showNames()
    alignRow()
    if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
    if (!prefs.getBoolean("tip_move", false)) {
        prefs.edit().putBoolean("tip_move", true).apply()
        toast(getString(R.string.tip_move), long = true)
    }
}

/** The average colour of the opaque pixels along a drawable's edge: what a small icon is framed with. */
internal fun MainActivity.edgeColor(d: Drawable): Int {
    val side = 48
    val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bounds = d.copyBounds()
    d.setBounds(0, 0, side, side)
    d.draw(canvas)
    d.bounds = bounds
    val ring = side / 8
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    for (y in 0 until side) for (x in 0 until side) {
        if (x >= ring && x < side - ring && y >= ring && y < side - ring) continue
        val p = bmp.getPixel(x, y)
        if (Color.alpha(p) < 200) continue
        r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
    }
    bmp.recycle()
    return if (n == 0) 0xFF2A2F36.toInt() else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

internal fun MainActivity.bannerTile(a: App): View? {
    if (a.pkg.startsWith(HDMI)) return null
    val own = appBanner(a)
    val w = rowTileWidth()
    val h = w * 9 / 16
    // No banner, or one too small to fill the tile: the app's own icon (its native colours) centred on a
    // rectangle in the icon's edge colour, so the tile is as full as a banner.
    val fit = own == null || own.intrinsicWidth in 1 until rowTileWidth(ROW_TILES)
    val banner = if (fit) own ?: a.icon else own!!
    // Drawn once, centre-cropped, at the tile's exact size: a row of banners then costs ~80 KB each, not the
    // banner's own size, and the full-size banner isn't kept.
    val bw = banner.intrinsicWidth.takeIf { it > 0 } ?: h
    val bh = banner.intrinsicHeight.takeIf { it > 0 } ?: h
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    (banner as? BitmapDrawable)?.isFilterBitmap = true
    if (fit) {
        val box = if (own == null) h * 0.72f else h * 0.9f
        val scale = minOf(box * (if (own == null) 1f else w.toFloat() / h) / bw, box / bh)
        val dw = (bw * scale).toInt()
        val dh = (bh * scale).toInt()
        val canvas = Canvas(bmp)
        canvas.drawColor(edgeColor(banner))
        banner.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
        banner.draw(canvas)
    } else {
        val scale = maxOf(w.toFloat() / bw, h.toFloat() / bh)
        val dw = (bw * scale).toInt()
        val dh = (bh * scale).toInt()
        banner.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
        banner.draw(Canvas(bmp))
    }
    // Monochrome banner: grey at rest, in colour while selected, as the marks brighten in dark mode.
    val grey = iconStyle() == "bannermono"
    val rest = if (dark) 0.8f else 1f
    val pic = ImageView(this).apply {
        setImageBitmap(bmp)
        scaleType = ImageView.ScaleType.FIT_XY
        if (grey) { colorFilter = GREYSCALE; alpha = rest }
    }
    // A colour banner rests dimmed a little, so the selected one, risen out of the row, also stands out in
    // brightness. The dim fades over the same 160 ms as the lift.
    val dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = if (grey) 0f else BANNER_DIM }
    val col = FrameLayout(this).apply {
        addView(pic, MATCH_PARENT, MATCH_PARENT)
        addView(dim, MATCH_PARENT, MATCH_PARENT)
        tag = a.pkg
        setOnClickListener { launch(a) }
    }
    style(col, dp(12), card = false, lift = 1.12f) { focused ->
        if (!grey) dim.animate().alpha(if (focused) 0f else BANNER_DIM).setDuration(160).start()
        if (!grey) return@style
        pic.colorFilter = if (focused) null else GREYSCALE
        pic.animate().alpha(if (focused) 1f else rest).setDuration(160).start()
    }
    return col
}

/** The home row shows marks only by default (see rowNames); the All apps grid always labels them. */
internal fun MainActivity.appTile(a: App, labeled: Boolean = true, window: Boolean = false): View {
    if (!labeled && !window && isBannerStyle(iconStyle())) bannerTile(a)?.let { it.contentDescription = a.label; return it }
    val logo = LOGOS[a.pkg]
    val custom = (if (!labeled && !a.pkg.startsWith(HDMI) && !isBannerStyle(iconStyle())) AppPictures.read(this, a.pkg, banner = false) else null)
        ?.apply { if (!colourIcons()) colorFilter = GREYSCALE }
        ?: customIcon(a.pkg)
    val name = a.label.takeIf { labeled }
    // Colour mode keeps the same marks, tinted with the brand colour (or the colour the app's icon is mostly).
    val colour = colourIcons()
    // A banner style, an app with no banner: its own colour behind its mark in white, so the row doesn't mix pictures
    // and grey squares. HDMI tiles keep the plain mark.
    val onColour = !labeled && isBannerStyle(iconStyle()) && !a.pkg.startsWith(HDMI)
    val t = when {
        custom != null -> tile(name, custom, false, a.pkg) { launch(a) }
        a.pkg.startsWith(HDMI) -> tile(name, a.icon, true, a.pkg,
            tint = BRAND_COLOURS[hdmiIcon(a.label, true)]?.takeIf { colour }) { launch(a) }
        logo != null -> tile(name.takeIf { !logo.second }, getDrawable(logo.first)!!, true, a.pkg,
            wordmarkInset = if (!logo.second) null else if (labeled) dp(28) else (dp(14) * rowScale()).toInt(),
            tint = if (onColour && BRAND_COLOURS[a.pkg] != null) Color.WHITE else BRAND_COLOURS[a.pkg]?.takeIf { colour }) { launch(a) }
            .also { if (onColour) BRAND_COLOURS[a.pkg]?.let { c -> it.backgroundTintList = ColorStateList.valueOf(deepen(c)) } }
        else -> cachedGlyph(a)?.let { g ->
            val accent = cachedAccent(a)
            tile(name, g, true, a.pkg, tint = if (onColour && accent != null) Color.WHITE else if (colour) accent else null) { launch(a) }
                .also { if (onColour && accent != null) it.backgroundTintList = ColorStateList.valueOf(deepen(accent)) }
        }
            ?: if (colour || onColour) tile(name, a.icon.constantState?.newDrawable(resources) ?: a.icon, false, a.pkg) { launch(a) }
            // A copy: greying the shared icon itself would keep it grey after switching to colour.
            else tile(name, (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { colorFilter = GREYSCALE }, false, a.pkg) { launch(a) }
    }
    t.contentDescription = a.label
    return t
}

/** The apps that are neither favourites nor hidden: rows of the shelf's tile size under the shelf. */
internal fun MainActivity.renderMoreApps() {
    val focused = currentFocus?.takeIf { it.parent?.parent === moreApps }
    val tag = focused?.tag
    fun tiles() = (0 until moreApps.childCount).flatMap { r -> (moreApps.getChildAt(r) as ViewGroup).let { row -> (0 until row.childCount).map { row.getChildAt(it) } } }
    val flat = focused?.let { tiles().indexOf(it) }
    moreApps.removeAllViews()
    val rowHidden = rowHiddenSet()
    val hiddenHome = homeHiddenSet()
    val list = sortedApps().filter { it.pkg in rowHidden && it.pkg !in hiddenHome && !it.pkg.startsWith(HDMI) }
    moreApps.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    val gap = dp(GRID_GAP)
    val w = rowTileWidth()
    val tileH = if (isBannerStyle(iconStyle())) w * 9 / 16 else (dp(76) * rowScale()).toInt()
    list.chunked(rowTiles()).forEachIndexed { r, chunk ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        chunk.forEachIndexed { i, a ->
            val t = appTile(a, labeled = false)
            t.id = View.generateViewId()
            row.addView(t, LinearLayout.LayoutParams(w, tileH).apply { if (i > 0) marginStart = gap })
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, f ->
                own?.onFocusChange(v, f)
                if (f) lastMore = v
            }
        }
        moreApps.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(if (r == 0) 28 else 22) })
    }
    if (focused != null) {
        val all = tiles()
        (all.firstOrNull { it.tag == tag } ?: all.getOrNull(minOf(flat ?: 0, all.size - 1)) ?: appRow.getChildAt(0))?.requestFocus()
    }
}

/** Keeps what has focus on screen when the home is taller than the screen: the home moves up under the top bar as focus goes down the grid. */
internal fun MainActivity.ensureHomeVisible(v: View?) {
    val content = findViewById<View>(R.id.content)
    if (v == null || settingsScrim.visibility == View.VISIBLE || allApps.visibility == View.VISIBLE) return
    var y = 0
    var x: View = v
    while (x !== content) {
        y += x.top
        x = x.parent as? View ?: return // not on the home
    }
    val pad = dp(24)
    val bottom = content.height - pad
    var to = 0f
    if (y + v.height > bottom) to = (bottom - (y + v.height)).toFloat()
    if (y + to < pad) to = (pad - y).toFloat().coerceAtMost(0f)
    // Fullscreen TMDB: the apps below the shelf come up over a shade, so they read on any picture.
    moreShade.animate().alpha(if (to < 0f && billboard.visibility == View.VISIBLE) 1f else 0f).setDuration(220).start()
    if (to == content.translationY) return
    content.animate().translationY(to).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}

internal fun MainActivity.moreMenu(v: View) {
    val pkg = v.tag as String
    val name = appLabel(pkg, v)
    if (locked()) return holdMenu(v, listOf(
        getString(R.string.open) to { v.performClick(); Unit },
        getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); moreMenu(v) } },
    ), title = name)
    // The shelf holds as many apps as the row has tiles: a full shelf asks you to unpin one first.
    val room = rowTiles()
    val pinned = sortedApps().count { inRow(it.pkg, rowHiddenSet()) }
    fun pin() {
        if (pinned >= room) return toast(getString(R.string.more_shelf_full, room), long = true)
        prefs.edit().putStringSet("row_hidden", rowHiddenSet() - pkg).apply()
        val rest = sortedApps().map { it.pkg }.filter { it != pkg }
        val favs = rest.filter { inRow(it, rowHiddenSet()) }
        saveOrder(favs + pkg + (rest - favs.toSet()))
        renderApps()
        toast(getString(R.string.more_pinned))
    }
    holdMenu(v, listOfNotNull(
        getString(R.string.more_pin) to { pin() },
        getString(R.string.more_hide) to {
            prefs.edit().putStringSet("home_hidden", homeHiddenSet() + pkg).apply()
            renderMoreApps()
            toast(getString(R.string.more_hidden))
        },
        getString(R.string.app_info) to {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        },
        if (canUninstall(pkg)) getString(R.string.uninstall) to { uninstall(pkg) } else null,
    ), title = name)
}

/** Hold OK on a row app: Move (OK places it), Remove from row (stays in All apps), App info. Locked: Open only. */
internal fun MainActivity.appMenu(v: View) {
    val pkg = v.tag as String
    val name = appLabel(pkg, v)
    if (locked()) return holdMenu(v, listOf(
        getString(R.string.open) to { v.performClick(); Unit },
        getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); appMenu(v) } },
    ), title = name)
    holdMenu(v, listOfNotNull(
        getString(R.string.move) to { startEdit(grab = v, once = true) },
        if (pkg.startsWith(HDMI)) null else getString(R.string.change_app_picture) to {
            appPictures(pkg, v.contentDescription.toString())
        },
        getString(R.string.more_unpin) to {
            val i = appRow.indexOfChild(v)
            // An input just unpins: it is in the Sources list anyway, so there is nothing to hide.
            if (pkg.startsWith(HDMI)) prefs.edit().putStringSet("inputs_pinned", pinnedInputs() - pkg).apply()
            else prefs.edit().putStringSet("row_hidden", rowHiddenSet() + pkg).apply()
            renderApps()
            appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
            toast(getString(R.string.removed_from_row))
        },
        if (pkg.startsWith(HDMI)) null else getString(R.string.more_hide) to {
            val i = appRow.indexOfChild(v)
            prefs.edit().putStringSet("row_hidden", rowHiddenSet() + pkg).putStringSet("home_hidden", homeHiddenSet() + pkg).apply()
            renderApps()
            appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
            toast(getString(R.string.more_hidden))
        },
        if (pkg.startsWith(HDMI)) null else getString(R.string.app_info) to {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        },
        if (canUninstall(pkg)) getString(R.string.uninstall) to { uninstall(pkg) } else null,
    ), title = name)
}

internal fun MainActivity.startEdit(grab: View?, once: Boolean = false) {
    editing = true
    moveOnce = once
    appsTitle.text = if (once) getString(R.string.moving_hint) else getString(R.string.editing_hint)
    appsTitle.visibility = View.VISIBLE
    (grab ?: appRow.getChildAt(0)).requestFocus()
    setGrabbed(grab)
}

internal fun MainActivity.openAllApps(recent: Boolean = false, at: Int = -1) {
    val selected = if (recent && recentAppsOpen) currentFocus?.tag else null
    if (recent) {
        activeMenu?.dismiss()
        setupDialog?.dismiss()
    }
    // Back from Recent apps returns focus to where it was (a menu is its own window: then its icon).
    if (recent && !recentAppsOpen) recentsBack = currentFocus?.let { WeakReference(it) to (it.tag as? String) }
    val opening = recent && !recentAppsOpen
    recentAppsOpen = recent
    allAppsGrid.removeAllViews()
    recentWindowRow.removeAllViews()
    recentWindows.visibility = View.GONE
    allAppsGrid.visibility = if (recent) View.GONE else View.VISIBLE
    val margin = if (recent) 0 else dp(48)
    allApps.setPadding(margin, if (recent) resources.displayMetrics.heightPixels / 14 else dp(28), margin, dp(28))
    // Recent apps sits on the bare wallpaper, like the home screen: no scrim (text colour and shadow give contrast).
    allApps.setBackgroundColor(if (recent || glassActive()) Color.TRANSPARENT else ground)
    // Recent apps titles itself like Continue Watching; All apps keeps its own title.
    val title = findViewById<TextView>(R.id.allAppsTitle).apply {
        setText(if (recent) R.string.recent_apps else R.string.all_apps)
        setTextColor(fg)
        if (!recent) setShadowLayer(0f, 0f, 0f, 0)
        setPaddingRelative(if (recent) dp(48) else 0, 0, 0, 0)
        typeface = Typeface.create(if (recent) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        textSize = if (recent) 14f else 20f
        alpha = if (recent) 0.7f else 1f
    }
    val list = sortedApps().let { all -> if (recent) recentApps(all.filter { !it.pkg.startsWith(HDMI) },
        removed = { prefs.getLong("r_${it.pkg}", 0) }) { prefs.getLong("t_${it.pkg}", 0) } else all }
    val info = findViewById<TextView>(R.id.allAppsInfo).apply {
        visibility = if (recent && list.isEmpty()) View.VISIBLE else View.GONE
        isFocusable = list.isEmpty()
        isFocusableInTouchMode = list.isEmpty()
        // Without the Home service Nooko Launcher sees only the apps it opens itself.
        setText(if (HomeOverride.running != null) R.string.recent_apps_empty_any else R.string.recent_apps_empty)
        textSize = 18f
        (layoutParams as ViewGroup.MarginLayoutParams).topMargin = if (recent) resources.displayMetrics.heightPixels / 4 else dp(26)
    }
    recentClear.visibility = if (recent && list.isNotEmpty()) View.VISIBLE else View.GONE
    if (recent) {
        recentWindows.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        recentsFocus = (selected as? String)?.takeIf { pkg -> list.any { it.pkg == pkg } }
            ?: list.getOrNull(if (at >= 0) minOf(at, list.size - 1) else 0)?.pkg
        buildRecentWindows(list)
        // The title and the empty text sit on the wallpaper: one colour picked against it once laid out (see fitInk).
        allApps.post {
            if (!recentAppsOpen) return@post
            val color = inkBehind(listOf(title, if (list.isEmpty()) info else title))
            for (v in listOf(title, info)) ink(v, color)
        }
        // Opening: the windows settle in from slightly smaller, as Nooko Launcher's sheets do.
        if (opening) recentWindows.apply {
            val animate = animationsOn() && ValueAnimator.areAnimatorsEnabled()
            alpha = if (animate) 0f else 1f
            scaleX = if (animate) 0.94f else 1f
            scaleY = scaleX
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (animate) 220 else 0).setInterpolator(EASE).start()
        }
    } else {
        val gap = dp(10)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * 5) / 6
        for (a in list) {
            allAppsGrid.addView(appTile(a), GridLayout.LayoutParams().apply {
                this.width = width
                height = dp(76)
                setMargins(0, 0, gap, gap)
            })
        }
    }
    allApps.visibility = View.VISIBLE
    // Glass shows the wallpaper here, so the home screen under the grid has to go; without glass the opaque
    // background covers it anyway.
    findViewById<View>(R.id.content).visibility = if (recent || glassActive()) View.INVISIBLE else View.VISIBLE
    homeFocusable(false)
    val row = if (recent) recentWindowRow else allAppsGrid
    val first = (row.getChildAt(if (at >= 0) minOf(at, row.childCount - 1) else 0) as? ViewGroup)?.getChildAt(0)?.takeIf { recent }
    val target = selected?.let { row.findViewWithTag<View>(it) } ?: first ?: row.getChildAt(0) ?: info
    target.requestFocus()
}

/**
 * Recent apps: one frosted window per app, in the home row's tile style (glass or rim card, the same rise on
 * focus). A title bar with the app's own icon and name sits on the pane; under it, inset and rounded, the app's
 * last screen, its Continue Watching art, or its banner or icon. The selected window sits in the centre, the
 * others smaller beside it. Under the selected window: Remove.
 */
internal fun MainActivity.buildRecentWindows(list: List<App>) {
    val screen = resources.displayMetrics
    val width = minOf(screen.widthPixels * 45 / 100, screen.heightPixels * 4 / 5)
    val inset = dp(8)
    val bar = dp(44)
    val height = bar + (width - inset * 2) * 9 / 16 + inset
    val gap = dp(GRID_GAP)
    val edge = (screen.widthPixels - width) / 2
    recentWindowRow.setPadding(edge, dp(16), edge, dp(8))
    recentMotion?.cancel()
    val previews = prefs.getBoolean(HomeOverride.RECENT_PREVIEWS, false)
    val clear = recentClear
    for ((index, app) in list.withIndex()) {
        val preview = if (previews) RecentPreviews.get(this, app.pkg) else null
        val art = watchList.firstOrNull { it.pkg == app.pkg }?.image?.takeIf { preview == null }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@buildRecentWindows).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                addView(ImageView(this@buildRecentWindows).apply {
                    setImageDrawable(app.icon.constantState?.newDrawable(resources) ?: app.icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label(app.label, 15f).apply {
                    setPaddingRelative(dp(10), 0, 0, 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, bar))
            addView(windowContent(app, preview, art, width), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
                setMargins(inset, 0, inset, inset)
            })
            style(this, dp(14), card = true)
            setOnClickListener { launch(app) }
        }
        window.id = View.generateViewId()
        window.tag = app.pkg
        window.contentDescription = app.label
        // No system shadow: through a see-through pane this large, its corner-to-corner shape shows as a dark X.
        // The glass draws its own shade along the bottom rim (see GlassLayer.light).
        window.elevation = 0f
        window.nextFocusUpId = clear.id
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            alpha = 0f
        }
        // No Resume: OK on the window already returns to the app where it was left.
        val buttons = listOf(pill(getString(R.string.recent_remove)) { removeWindow(card, app.pkg, index) })
        for ((i, b) in buttons.withIndex()) {
            actions.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(12)
            })
            b.nextFocusUpId = window.id
            b.nextFocusDownId = b.id
        }
        buttons.first().nextFocusLeftId = buttons.first().id
        buttons.last().nextFocusRightId = buttons.last().id
        window.nextFocusDownId = buttons.first().id
        card.addView(window, LinearLayout.LayoutParams(MATCH_PARENT, height))
        card.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        // The buttons show while the window or one of them has focus.
        val showActions = Runnable {
            val on = window.isFocused || buttons.any { it.isFocused }
            actions.animate().alpha(if (on) 1f else 0f).setDuration(160).start()
        }
        for (b in buttons) {
            val pillFocus = b.onFocusChangeListener
            b.setOnFocusChangeListener { v, focused -> pillFocus?.onFocusChange(v, focused); v.post(showActions) }
        }
        val tileFocus = window.onFocusChangeListener // the tile's own rise (see style)
        window.setOnFocusChangeListener { v, focused ->
            tileFocus?.onFocusChange(v, focused)
            v.animate().translationZ(0f).setDuration(0).start() // keeps the tile's rise, drops its shadow lift
            v.post(showActions)
            if (focused) {
                clear.nextFocusDownId = window.id
                moveWindows(index, width, gap)
            }
        }
        recentWindowRow.addView(card, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (index > 0) marginStart = gap
        })
    }
    // Start where focus will land (see openAllApps), without motion; the scroll waits for layout.
    val start = list.indexOfFirst { it.pkg == recentsFocus }.coerceAtLeast(0)
    recentsFocus = null
    placeWindows(start.toFloat(), width, gap)
    recentWindows.post { if (recentAppsOpen) placeWindows(recentAt, width, gap) }
}
