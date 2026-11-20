package dev.nooko.launcher

import dev.nooko.launcher.MainActivity.Companion.AREA_ALL
import dev.nooko.launcher.MainActivity.Companion.CARDS_ROWS
import dev.nooko.launcher.MainActivity.Companion.CHOSEN
import dev.nooko.launcher.MainActivity.Companion.CLOCK_SIZES
import dev.nooko.launcher.MainActivity.Companion.CLOCK_STYLES
import dev.nooko.launcher.MainActivity.Companion.DARK_BG
import dev.nooko.launcher.MainActivity.Companion.DECELERATE
import dev.nooko.launcher.MainActivity.Companion.GLASS_STYLES
import dev.nooko.launcher.MainActivity.Companion.HDMI
import dev.nooko.launcher.MainActivity.Companion.ICON_STYLES
import dev.nooko.launcher.MainActivity.Companion.PANEL_EDGE
import dev.nooko.launcher.MainActivity.Companion.PHOTO_EVERY
import dev.nooko.launcher.MainActivity.Companion.PIN_KEYS
import dev.nooko.launcher.MainActivity.Companion.PIN_TRIES
import dev.nooko.launcher.MainActivity.Companion.REPO
import dev.nooko.launcher.MainActivity.Companion.ROW_ALIGN
import dev.nooko.launcher.MainActivity.Companion.ROW_ART
import dev.nooko.launcher.MainActivity.Companion.ROW_HELP
import dev.nooko.launcher.MainActivity.Companion.ROW_ICONS
import dev.nooko.launcher.MainActivity.Companion.ROW_SHOW
import dev.nooko.launcher.MainActivity.Companion.ROW_SIZES
import dev.nooko.launcher.MainActivity.Companion.SECTION_AREAS
import dev.nooko.launcher.MainActivity.Companion.SECTION_ICONS
import dev.nooko.launcher.MainActivity.Companion.THEMES
import dev.nooko.launcher.MainActivity.Companion.TOGGLE_OFF
import dev.nooko.launcher.MainActivity.Companion.TOGGLE_ON
import dev.nooko.launcher.MainActivity.Companion.UNLOCKED_MS
import dev.nooko.launcher.MainActivity.Companion.UPDATE_STATUS
import dev.nooko.launcher.MainActivity.Companion.WALLPAPER_CARD
import dev.nooko.launcher.MainActivity.Companion.WALLPAPER_KEYS
import dev.nooko.launcher.MainActivity.Companion.WATCH_NEXT
import dev.nooko.launcher.MainActivity.Companion.WRITE_SECURE

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
 * The settings panel: its pages and rows, the layout lock, the remote keys, the backup and the updates, as extensions of
 * [MainActivity]. The views and the state stay in MainActivity; what they do lives here.
 */
internal fun MainActivity.pickKeys(focus: Int = 0) {
    val keys = Remap.all(this).entries.toList()
    showPage(getString(R.string.section_keys),
        listOf(page(getString(R.string.key_add)) { learnKey() }) + keys.map { (id, e) ->
            page(Remap.name(this, id), Remap.describe(this, e.short.ifEmpty { e.long })) { keyPage(id) }
        }, focus) { renderSettings(R.string.section_keys) }
}

/** Waits for the next key press (Back and the arrows are left alone so the page can always be left). */
internal fun MainActivity.learnKey() {
    if (HomeOverride.running == null) return Toast.makeText(this, getString(R.string.home_on_hint), Toast.LENGTH_LONG).show()
    val hint = label(getString(R.string.key_press_prompt), 15f).apply {
        maxLines = 5
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    Remap.learn = { e ->
        val id = Remap.id(e) // read now: the framework recycles the event as soon as the service returns
        ui.post { Remap.learn = null; keyPage(id) }
    }
    showPage(getString(R.string.key_add), emptyList(), top = hint) { Remap.learn = null; pickKeys() }
    pageExit = { Remap.learn = null }
}

internal fun MainActivity.keyPage(id: String, focus: Int = 0) {
    val e = Remap.all(this)[id] ?: Remap.Entry("", "")
    showPage(Remap.name(this, id), listOf(
        page(getString(R.string.key_short), Remap.describe(this, e.short)) { pickAction(id, false) },
        page(getString(R.string.key_long), Remap.describe(this, e.long)) { pickAction(id, true) },
        page(getString(R.string.key_remove)) { Remap.put(this, id, "", ""); pickKeys() },
    ), focus) { pickKeys() }
}

/** Saves [action] as what [id] does on a press (or a hold) and returns to the key's page. */
internal fun MainActivity.setAction(id: String, hold: Boolean, action: String) {
    val e = Remap.all(this)[id] ?: Remap.Entry("", "")
    if (hold) Remap.put(this, id, e.short, action) else Remap.put(this, id, action, e.long)
    keyPage(id, if (hold) 1 else 0)
}

internal fun MainActivity.pickAction(id: String, hold: Boolean) {
    val e = Remap.all(this)[id] ?: Remap.Entry("", "")
    val now = if (hold) e.long else e.short
    fun row(action: String, name: String) = page(name, if (action == now) CHOSEN else "") { setAction(id, hold, action) }
    val rows = listOf(
        row("", getString(R.string.key_unset)),
        row("none", getString(R.string.key_block)),
        page(getString(R.string.key_open_app), if (now.startsWith("app:") || now.startsWith("hdmi:")) Remap.describe(this, now) else "") {
            pickApp(id, hold, now)
        },
    ) + Remap.MEDIA.map { (k, v) -> row("media:$k", getString(v.second)) } +
        Remap.SYS.map { (k, v) -> row("sys:$k", getString(v.second)) } +
        Remap.OPEN.map { (k, v) -> row("open:$k", getString(v.second)) }
    showPage(getString(if (hold) R.string.key_long else R.string.key_short), rows) { keyPage(id, if (hold) 1 else 0) }
}

internal fun MainActivity.pickApp(id: String, hold: Boolean, now: String) {
    // Every input counts, with or without a signal: sortedApps() has only the ones pinned to the row.
    showPage(getString(R.string.key_open_app), (sources + sortedApps().filter { !it.pkg.startsWith(HDMI) }).map { a ->
        val action = if (a.pkg.startsWith(HDMI)) "hdmi:" + a.pkg.removePrefix(HDMI) else "app:" + a.pkg
        page(a.label, if (action == now) CHOSEN else "") { setAction(id, hold, action) }
    }) { pickAction(id, hold) }
}

internal fun MainActivity.openLauncherSettings(at: Int = lastSettings) {
    showPanel()
    renderSettings(at)
}

internal fun MainActivity.showPanel() {
    panelFrom = currentFocus
    // Dark: true black, set apart from the home screen by a hairline on its left edge.
    showPanelGlass()
    findViewById<TextView>(R.id.settingsTitle).apply { setTextColor(focusInk()); panelShadow() }
    for (id in listOf(R.id.settingsCrumb, R.id.settingsHelp)) findViewById<TextView>(id).apply { setTextColor(valueInk()); panelShadow() }
    settingsScrim.background = spotlight.apply { reset() }
    pages.clear()
    settingsScrim.visibility = View.VISIBLE
    homeFocusable(false)
}

/** The panel is frosted glass over the wallpaper when Glass is on, and the plain surface otherwise. */
internal fun MainActivity.showPanelGlass() {
    val panel = findViewById<GlassBox>(R.id.settingsPanel)
    if (glassActive()) {
        panel.background = null
        panel.setWillNotDraw(false)
        styleGlass(panel.glass, dp(22).toFloat(), flatRight = true)
    } else {
        panel.setWillNotDraw(true)
        panel.background = if (!dark) ColorDrawable(0xFFF7F8FA.toInt())
            else LayerDrawable(arrayOf(ColorDrawable(DARK_BG), ColorDrawable(PANEL_EDGE))).apply {
                setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
                setLayerWidth(1, 1)
            }
    }
    panel.invalidate()
    if (settingsScrim.background === spotlight) spotlight.invalidateSelf()
    settingsScrim.invalidate()
}

internal fun MainActivity.closeLauncherSettings() {
    val updateTheme = wallpaperPickerOpen() && wantDark() != dark
    finishPhotoPreview(false, returnToPicker = false)
    wallPreview = null
    settingsScrim.animate().cancel()
    settingsScrim.alpha = 1f
    pageExit?.invoke()
    pageExit = null
    pageBack = null
    settingsScrim.visibility = View.GONE
    homeFocusable(allApps.visibility != View.VISIBLE) // the PIN can open over All apps
    (panelFrom?.takeIf { it.isAttachedToWindow } ?: findViewById(R.id.launcherSettings)).requestFocus()
    panelFrom = null
    if (updateTheme) recreate()
}

/**
 * Nooko Launcher settings, as a short list of sections that each open a page. [at] is the string id of the row to
 * focus: a row inside a section opens that section on it; a section or a top-level row focuses it in the
 * main list. Rows are found by name, so adding one never moves the others.
 */
internal fun MainActivity.renderSettings(at: Int = 0) {
    val theme = prefs.getString("theme", "light")!!
    val sort = prefs.getString("sort", "manual")!!
    val hidden = prefs.getStringSet("hidden_watch", emptySet())!!.size
    val weather = prefs.getString("weather", "off")!!
    val unit = if (weather == "off") prefs.getString("wx_unit", "c")!! else weather
    val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
    val rowHidden = rowHiddenSet().count { pkg -> apps.any { it.pkg == pkg } }
    val chFlip = prefs.getStringSet("ch_flip", emptySet())!!
    val channelsOn = (if (WATCH_NEXT in chFlip) 0 else 1) + appChannels().distinctBy { (_, p, n) -> channelKey(p, n) }.count { (_, p, n) -> channelOn(n, channelKey(p, n), chFlip) }
    val names = rowNames()
    // Rows with more than two values also step back with Left; two-value rows flip either way.
    val steps = mutableMapOf<Int, (Int) -> Unit>(
        R.string.clock_size to { d ->
            prefs.edit().putString("clock_size", CLOCK_SIZES.keys.toList().step(clockSize(), d).toString()).apply()
            findViewById<TextView>(R.id.clock).textSize = clockSize().toFloat()
            findViewById<View>(R.id.clock).post { fitInk() } // the clock now covers other pixels
            renderSettings(R.string.clock_size)
        },
        R.string.clock_style to { d ->
            prefs.edit().putString("clock_style", CLOCK_STYLES.keys.toList().step(prefs.getString("clock_style", "light")!!, d)).apply()
            findViewById<TextView>(R.id.clock).typeface = clockFace()
            findViewById<View>(R.id.clock).post { fitInk() } // the clock now covers other pixels
            renderSettings(R.string.clock_style)
        },
        R.string.icon_style to { d ->
            prefs.edit().putString("icon_style", ICON_STYLES.keys.toList().step(iconStyle(), d)).apply()
            renderApps()
            renderSettings(R.string.icon_style)
        },
        R.string.theme to { d ->
            prefs.edit().putString("theme", THEMES.step(theme, d)).putBoolean("reopen_settings", true).apply()
            recreate() // colours are applied once per activity; reopen the panel after
        },
        R.string.row_size to { d ->
            prefs.edit().putString("row_size", ROW_SIZES.keys.toList().step(rowSize(), d)).apply()
            renderApps()
            renderSettings(R.string.row_size)
        },
        R.string.row_position to { d ->
            prefs.edit().putString("row_align", ROW_ALIGN.keys.toList().step(rowAlign(), d)).apply()
            alignRow()
            renderSettings(R.string.row_position)
        },
        R.string.app_names to { d ->
            prefs.edit().putString("names", listOf("focus", "always", "never").step(names, d)).apply()
            renderApps()
            renderSettings(R.string.app_names)
        },
        R.string.show_row to { d ->
            prefs.edit().putString("row_show", ROW_SHOW.keys.toList().step(rowShow(), d)).apply()
            placeRow(animate = false)
            renderSettings(R.string.show_row)
        },
        R.string.apps_when_hidden to { _ ->
            prefs.edit().putBoolean("row_drop", !rowDrops()).apply()
            placeRow(animate = false)
            renderSettings(R.string.apps_when_hidden)
        },
        R.string.show_icons to { _ ->
            prefs.edit().putBoolean("cw_colour", !cwColour()).apply()
            watchKey = null // rebuild the cards with the new source icons
            showWatchNext(Cards(cardSources, cardCurrent, watchList))
            renderSettings(R.string.show_icons)
        },
        R.string.glass to { d ->
            val styles = GLASS_STYLES
            prefs.edit().putString("glass_style", styles.step(glassStyle().let { if (it in styles) it else GlassStyle.FROSTED }, d)?.key ?: "off").apply()
            // Clear's sharp picture is made only while Clear is the style: load the wallpaper again to make it.
            if (glassStyle() == GlassStyle.CLEAR && sharpPixels == null && Backdrop.of(currentWallpaper()) == Backdrop.PICTURE) loadWallpaper()
            else applyGlass()
            renderSettings(R.string.glass)
        },
    )
    val rows = linkedMapOf<Int, Triple<String, String, () -> Unit>>(
        R.string.edit_apps_row to Triple(getString(R.string.edit_apps_row), "") { closeLauncherSettings(); startEdit(grab = null) },
        R.string.theme to Triple(getString(R.string.theme), getString(mapOf("light" to R.string.theme_light, "dark" to R.string.theme_dark, "wallpaper" to R.string.theme_wallpaper, "auto" to R.string.theme_auto)[theme] ?: R.string.theme_light)) {
            steps[R.string.theme]!!(1)
        },
        R.string.wallpaper to Triple(getString(R.string.wallpaper), wallpaperName(currentWallpaper())) { pickWallpaper(dark) },
        R.string.glass to Triple(getString(R.string.glass), getString(glassName())) {
            steps[R.string.glass]!!(1)
        },
        R.string.app_animations to Triple(getString(R.string.app_animations), toggle(animationsOn())) {
            prefs.edit().putBoolean("app_anim", !animationsOn()).apply()
            renderSettings(R.string.app_animations)
        },
        R.string.folder_photo_changes to Triple(getString(R.string.folder_photo_changes), getString(PHOTO_EVERY[photoEvery()]!!)) { pickPhotoEvery() },
        R.string.sort_apps to Triple(getString(R.string.sort_apps), if (sort == "manual") getString(R.string.manual) else getString(R.string.most_used)) {
            prefs.edit().putString("sort", if (sort == "manual") "usage" else "manual").apply()
            renderApps()
            renderSettings(R.string.sort_apps)
        },
        R.string.tmdb_mode to Triple(getString(R.string.tmdb_mode), getString(if (settings.tmdbFull) R.string.tmdb_full else R.string.tmdb_cards)) {
            val full = settings.tmdbFull
            settings.tmdbFull = !full
                prefs.edit().putBoolean("reopen_settings", true).apply()
            recreate() // the fullscreen look is a dark one: colours are applied once per activity
        },
        R.string.tmdb_refresh to Triple(getString(R.string.tmdb_refresh), "") {
            // New titles, in a new random order: the list, the details and the pictures are fetched again.
            heroList = emptyList()
            heroAt = 0
            heroDetails.clear()
            heroBg.evictAll()
            refreshHero()
            toast(getString(R.string.tmdb_refreshed))
        },
        R.string.tmdb_region to Triple(getString(R.string.tmdb_region), settings.tmdbRegion
            ?: getString(R.string.tmdb_region_device, Tmdb.region(prefs))) { pickTmdbRegion() },
        R.string.tmdb_services to Triple(getString(R.string.tmdb_services), settings.tmdbServiceIds.size.let { if (it == 0) getString(R.string.tmdb_services_none) else getString(R.string.n_on, it) }) { pickTmdbServices() },
        R.string.wn_toggle to Triple(getString(R.string.wn_toggle), getString(if (settings.watchNextOn) R.string.on else R.string.off)) {
            settings.watchNextOn = !settings.watchNextOn
            reloadHero()
            renderSettings(R.string.wn_toggle)
        },
        R.string.wn_service to Triple(getString(R.string.wn_service), WatchNext.PROVIDERS.getValue(WatchNext.provider(prefs))) { pickWatchNextService() },
        R.string.wn_account to Triple(getString(R.string.wn_account),
            getString(if (WatchNext.ready(this, WatchNext.provider(prefs))) R.string.wn_signed_in else R.string.wn_not_set)) { watchNextAccount() },
        R.string.fav_sort to Triple(getString(R.string.fav_sort), getString(if (favSortUsage()) R.string.most_used else R.string.fav_custom)) {
            settings.favSortUsage = !favSortUsage()
            renderApps()
            renderSettings(R.string.fav_sort)
        },
        R.string.apps_in_row to Triple(getString(R.string.apps_in_row), if (rowHidden == 0) getString(R.string.all) else getString(R.string.n_of_m, apps.size - rowHidden, apps.size)) { pickRowApps() },
        R.string.row_position to Triple(getString(R.string.row_position), getString(ROW_ALIGN[rowAlign()]!!.first)) {
            steps[R.string.row_position]!!(1)
        },
        R.string.app_names to Triple(getString(R.string.app_names), mapOf("focus" to getString(R.string.on_focus), "always" to getString(R.string.always), "never" to getString(R.string.never))[names]!!) {
            steps[R.string.app_names]!!(1)
        },
        R.string.icon_style to Triple(getString(R.string.icon_style), getString(ICON_STYLES.getValue(iconStyle()))) {
            steps[R.string.icon_style]!!(1)
        },
        R.string.row_size to Triple(getString(R.string.row_size), getString(ROW_SIZES.getValue(rowSize()).first)) {
            steps[R.string.row_size]!!(1)
        },
        R.string.restore_hidden to Triple(getString(R.string.restore_hidden), if (hidden == 0) getString(R.string.none_hidden) else resources.getQuantityString(R.plurals.show_again, hidden, hidden)) {
            prefs.edit().remove("hidden_watch").apply()
            watchKey = null
            refreshWatchNext()
            renderSettings(R.string.restore_hidden)
        },
        R.string.cw_apps to Triple(getString(R.string.cw_apps), if (cwOff == 0) getString(R.string.all_on) else getString(R.string.n_off, cwOff)) { pickWatchSources() },
        R.string.cw_channels to Triple(getString(R.string.cw_channels), getString(R.string.n_on, channelsOn)) { pickChannels() },
        R.string.row_name to Triple(getString(R.string.row_name), rowName()) { pickRowName() },
        R.string.show_icons to Triple(getString(R.string.show_icons), getString(if (cwColour()) R.string.colour else R.string.monochrome)) {
            steps[R.string.show_icons]!!(1)
        },
        R.string.show_row to Triple(getString(R.string.show_row), getString(ROW_SHOW.getValue(rowShow()))) { steps[R.string.show_row]!!(1) },
        R.string.cards_rows to Triple(getString(R.string.cards_rows), getString(CARDS_ROWS.getValue(cardsMode()))) { pickCardsRows() },
        R.string.apps_when_hidden to Triple(getString(R.string.apps_when_hidden), getString(if (rowDrops()) R.string.moves_down else R.string.stays_put)) {
            steps[R.string.apps_when_hidden]!!(1)
        },
        R.string.clock_size to Triple(getString(R.string.clock_size), getString(CLOCK_SIZES.getValue(clockSize()))) {
            steps[R.string.clock_size]!!(1)
        },
        R.string.clock_style to Triple(getString(R.string.clock_style), getString(CLOCK_STYLES.getValue(prefs.getString("clock_style", "light")!!))) {
            steps[R.string.clock_style]!!(1)
        },
        R.string.date_format to clockFormatRow(R.string.date_format, ClockFormat.DATE),
        R.string.time_format to clockFormatRow(R.string.time_format, ClockFormat.TIME),
        // "weather" is off/c/f as before; "wx_unit" keeps the unit while it's off.
        R.string.weather to Triple(getString(R.string.weather), toggle(weather != "off")) {
            if (weather == "off") prefs.edit().putString("weather", unit).apply()
            else prefs.edit().putString("wx_unit", weather).putString("weather", "off").apply()
            showWeather()
            ui.removeCallbacks(weatherTick)
            ui.post(weatherTick)
            renderSettings(R.string.weather)
        },
        R.string.weather_units to Triple(getString(R.string.weather_units), if (unit == "f") "°F" else "°C") {
            val next = if (unit == "f") "c" else "f"
            prefs.edit().putString("wx_unit", next).putString("weather", if (weather == "off") "off" else next).apply()
            showWeather()
            renderSettings(R.string.weather_units)
        },
        R.string.weather_location to Triple(getString(R.string.weather_location), if (manualCity()) prefs.getString("geo_city", null) ?: "" else "Automatic") { pickCity() },
        R.string.tmdb_row to Triple(getString(R.string.tmdb_row), getString(if (Tmdb.key(this) == null) R.string.off else R.string.on)) {
            configureTmdb(back = { renderSettings(R.string.tmdb_row) })
        },
        R.string.screensaver to Triple(getString(R.string.screensaver), when {
            downloading -> getString(R.string.downloading_gallery)
            !galleryOn() -> screensaverName()
            tmdbScreensaverOn() -> getString(R.string.tmdb_screensaver)
            else -> photoScreensaver()?.let { wallpaperName(it) } ?: getString(R.string.gallery)
        }) { pickScreensaver() },
        R.string.test_screensaver to Triple(getString(R.string.test_screensaver), "") { testScreensaver() },
        R.string.screensaver_timing to Triple(getString(R.string.screensaver_timing), screensaverDelayName()) { pickScreensaverDelay() },
        R.string.setup_home to Triple(getString(R.string.setup_home), "") { closeLauncherSettings(); startHomeSetup() },
        R.string.home_button to Triple(getString(R.string.home_button), toggle(homeServiceOn())) { toggleHomeButton() },
        R.string.smooth_volume to Triple(getString(R.string.smooth_volume), toggle(prefs.getBoolean("smooth_volume", false))) {
            prefs.edit().putBoolean("smooth_volume", !prefs.getBoolean("smooth_volume", false)).commit()
            HomeOverride.running?.applyKeyFilter()
            renderSettings(R.string.smooth_volume)
        },
        R.string.recent_previews to Triple(getString(R.string.recent_previews), toggle(prefs.getBoolean(HomeOverride.RECENT_PREVIEWS, false))) {
            if (prefs.getBoolean(HomeOverride.RECENT_PREVIEWS, false)) {
                prefs.edit().remove(HomeOverride.RECENT_PREVIEWS).apply()
                HomeOverride.running?.applyKeyFilter()
                RecentPreviews.clear(this)
                renderSettings(R.string.recent_previews)
            } else {
                homePopup(0, getString(R.string.recent_previews), getString(R.string.recent_previews_disclosure), null,
                    getString(R.string.recent_previews_enable) to {
                        prefs.edit().putBoolean(HomeOverride.RECENT_PREVIEWS, true).apply()
                        HomeOverride.running?.applyKeyFilter()
                        setupDialog?.dismiss()
                        renderSettings(R.string.recent_previews)
                    },
                    getString(R.string.cancel) to { setupDialog?.dismiss() })
            }
        },
        R.string.default_launcher to Triple(getString(R.string.default_launcher), if (isDefaultHome()) "Nooko Launcher" else getString(R.string.press_to_set)) { makeDefaultHome() },
        R.string.updates to Triple(getString(R.string.updates), when {
            updating -> getString(R.string.updating)
            checking -> getString(R.string.checking)
            availableUpdate() != null -> getString(R.string.update_available_row, availableUpdate())
            notesUnseen() -> getString(R.string.whats_new_title, myVersion())
            else -> getString(R.string.up_to_date_row, myVersion())
        }) { showUpdates() },
        R.string.lock_layout to Triple(getString(R.string.lock_layout), toggle(lockOn())) {
            if (!lockOn()) return@Triple setLock()
            prefs.edit().remove("lock_pin").apply()
            renderSettings(R.string.lock_layout)
        },
    )
    for (k in listOf(R.string.sort_apps, R.string.fav_sort, R.string.weather_units)) steps[k] = { rows[k]!!.third() }
    // Like the layout: behind the PIN. unlock() closes the panel after the PIN, so open it again.
    rows[R.string.section_keys] = page(getString(R.string.section_keys), "${keysSummary()}  ›") {
        unlock(back = { renderSettings(R.string.section_keys) }) {
            if (settingsScrim.visibility != View.VISIBLE) showPanel()
            pickKeys()
        }
    }
    val on = { b: Boolean -> getString(if (b) R.string.on else R.string.off) }
    // The wallpaper has its own page of pictures; the photo timer and the shows sources live there too.
    if (at in WALLPAPER_KEYS) return pickWallpaper(dark)
    // In the order things sit on the home screen, top to bottom, then the TV itself.
    val sections = listOf(
        Triple(R.string.section_style, "${rows[R.string.theme]!!.second} · ${getString(glassName())}",
            listOf(R.string.theme, R.string.glass, R.string.app_animations)),
        Triple(R.string.section_tmdb, rows[R.string.tmdb_row]!!.second, listOf(R.string.tmdb_row, R.string.tmdb_mode, R.string.tmdb_region, R.string.tmdb_services, R.string.tmdb_refresh)),
        Triple(R.string.section_app_row, "${rows[R.string.apps_in_row]!!.second} · ${rows[R.string.row_position]!!.second}",
            listOf(R.string.edit_apps_row, R.string.apps_in_row, R.string.fav_sort, R.string.sort_apps, R.string.icon_style, R.string.row_size, R.string.app_names, R.string.row_position)),
        // "When hidden" only means something once the row can hide.
        Triple(R.string.bottom_row, "${rowName()} · ${rows[R.string.show_row]!!.second}", listOfNotNull(R.string.show_row, R.string.apps_when_hidden.takeIf { rowShow() != "always" },
            R.string.row_name, R.string.show_icons, R.string.cw_apps, R.string.cw_channels, R.string.cards_rows, R.string.restore_hidden)),
        Triple(R.string.section_clock_weather, if (weather == "off") on(false) else "${rows[R.string.weather_units]!!.second} · ${prefs.getString("geo_city", null) ?: rows[R.string.weather_location]!!.second}",
            listOf(R.string.clock_style, R.string.clock_size, R.string.date_format, R.string.time_format,
                R.string.weather, R.string.weather_units, R.string.weather_location)),
        Triple(R.string.section_screensaver, rows[R.string.screensaver]!!.second, listOf(R.string.screensaver, R.string.test_screensaver, R.string.screensaver_timing)),
        // Home app is Android TV's own way (Google TV and Fire OS keep their home); Fire OS never passes keys to us, so no smooth volume there.
        Triple(R.string.section_remote_home, if (homeSetUp()) getString(R.string.home_button) else on(false),
            listOfNotNull(R.string.home_button, R.string.default_launcher.takeIf { !googleTv() && !fireTv() }, R.string.smooth_volume.takeIf { !fireTv() },
                R.string.recent_previews.takeIf { Build.VERSION.SDK_INT >= 30 && !fireTv() },
                R.string.section_keys, R.string.setup_home)),
    )
    val section = sections.firstOrNull { at in it.third }
    if (section != null) {
        lastSettings = section.first
        val keys = section.third
        // What Auto does today, and that Clear needs a still picture (see GlassLook.active), go in those rows' help.
        val help = { k: Int ->
            when {
                k == R.string.theme && theme == "auto" -> autoNote()
                k == R.string.theme && theme == "wallpaper" -> getString(R.string.help_theme_wallpaper)
                // Fire OS never passes the Home key to Nooko Launcher, so it can't see a double press there.
                k == R.string.home_button && fireTv() -> getString(R.string.help_home_button_fire)
                else -> ROW_HELP[k]?.let { getString(it) }
            }
        }
        // Choices show side by side (or under the title when long); the row's value is the picked one's name.
        val choices = mapOf(
            R.string.theme to listOf(R.string.theme_light to R.drawable.ms_light_mode, R.string.theme_dark to R.drawable.ms_dark_mode,
                R.string.theme_wallpaper to R.drawable.ms_wallpaper, R.string.theme_auto to R.drawable.ms_routine).map { (s, i) -> getString(s) to i },
            R.string.glass to listOf(R.string.off, R.string.glass_frosted, R.string.glass_clear).map { getString(it) to null },
            R.string.sort_apps to listOf(R.string.manual, R.string.most_used).map { getString(it) to null },
            R.string.fav_sort to listOf(R.string.fav_custom, R.string.most_used).map { getString(it) to null },
            R.string.app_names to listOf(R.string.on_focus, R.string.always, R.string.never).map { getString(it) to null },
            R.string.row_size to ROW_SIZES.values.map { getString(it.first) to null },
            R.string.row_position to ROW_ALIGN.values.map { getString(it.first) to null },
            R.string.show_row to ROW_SHOW.values.map { getString(it) to null },
            R.string.apps_when_hidden to listOf(R.string.stays_put, R.string.moves_down).map { getString(it) to null },
            R.string.show_icons to listOf(R.string.monochrome, R.string.colour).map { getString(it) to null },
            R.string.weather_units to listOf("°C" to null, "°F" to null),
            R.string.clock_style to CLOCK_STYLES.values.map { getString(it) to null },
            R.string.clock_size to CLOCK_SIZES.values.map { getString(it) to null },
        )
        val styles = keys.map { k ->
            val cost = k == R.string.smooth_volume && prefs.getBoolean("smooth_volume", false)
            val volumeNote = if (prefs.getBoolean(HomeOverride.RECENT_PREVIEWS, false) && Build.VERSION.SDK_INT >= 30)
                R.string.smooth_volume_preview_pause else R.string.smooth_volume_cost
            RowStyle(ROW_ICONS[k], sub = if (cost) getString(volumeNote) else null, choices = choices[k],
                help = help(k), area = SECTION_AREAS[section.first], art = ROW_ART[k],
                // Without the adb permission, Home opens Nooko Launcher is switched on in Android's Accessibility screen.
                ext = k == R.string.home_button && !homeServiceOn() && checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED)
        }
        return showPage(getString(section.first), keys.map { rows[it]!! }, keys.indexOf(at), steps = keys.map { steps[it] }, styles = styles,
            crumb = getString(R.string.atv_settings)) {
            renderSettings(section.first)
        }
    }
    val sectionRow = { key: Int ->
        val (k, value, keys) = sections.first { it.first == key }
        Triple(k, page(getString(k), "›") { renderSettings(keys[0]) }, RowStyle(SECTION_ICONS[k], value, help = ROW_HELP[k]?.let { getString(it) }, area = SECTION_AREAS[k]))
    }
    val group = { text: Int -> Triple(-1, page(getString(text)) {}, RowStyle(header = true)) }
    val wall = currentWallpaper()
    // Until Home opens Nooko Launcher one way or the other, a card on top leads to the setup; not after Home was turned off on purpose.
    val top = listOfNotNull(if (homeSetUp() || prefs.getBoolean("home_off", false)) null
            else Triple(R.string.finish_setup, page(getString(R.string.finish_setup), "›") { closeLauncherSettings(); startHomeSetup() },
                RowStyle(R.drawable.ms_home, getString(R.string.finish_setup_value), help = getString(R.string.help_finish_setup)))) +
        Triple(WALLPAPER_CARD, page(getString(R.string.wallpaper), wallpaperName(wall)) { pickWallpaper(dark) },
            RowStyle(thumb = wall to dark, help = getString(R.string.help_wallpaper), area = AREA_ALL, hero = true)) +
        group(R.string.group_home_screen) +
        listOf(R.string.section_style, R.string.section_tmdb, R.string.section_app_row, R.string.bottom_row, R.string.section_clock_weather, R.string.section_screensaver).map(sectionRow) +
        group(R.string.group_tv) +
        sectionRow(R.string.section_remote_home) +
        Triple(R.string.lock_layout, rows[R.string.lock_layout]!!, RowStyle(R.drawable.ms_lock, help = getString(R.string.lock_note))) +
        Triple(R.string.backup, page(getString(R.string.backup), "›") { showBackup() },
            RowStyle(R.drawable.ms_folder, help = getString(R.string.help_backup))) +
        Triple(R.string.updates, rows[R.string.updates]!!.let { (t, _, a) -> page(t, "›", a) },
            RowStyle(R.drawable.ms_system_update, rows[R.string.updates]!!.second, help = getString(R.string.help_updates))) +
        listOfNotNull(if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
            else Triple(R.string.preview_home_setup, page(getString(R.string.preview_home_setup)) { closeLauncherSettings(); previewHomeSetup() },
                RowStyle(R.drawable.ms_code)))
    lastSettings = 0
    showPage(getString(R.string.atv_settings), top.map { it.second }, maxOf(0, top.indexOfFirst { it.first == at }), styles = top.map { it.third }, back = null)
}

internal fun MainActivity.showPage(title: String, rows: List<Triple<String, String, () -> Unit>>, focus: Int = 0, top: View? = null,
                     steps: List<((Int) -> Unit)?> = emptyList(), styles: List<RowStyle?> = emptyList(), crumb: String? = null,
                     back: (() -> Unit)?) {
    pageExit?.invoke()
    pageExit = null
    pageBack = back
    // Deeper pages slide in from the right, ones already visited (Back) from the left; a redraw of the same page stays put.
    // With the crumb, so a section and a page inside it with the same name (Screensaver) count as two pages.
    val key = "$crumb › $title"
    val dir = when {
        pages.lastOrNull() == key -> 0
        key in pages -> -1.also { while (pages.last() != key) pages.removeAt(pages.size - 1) }
        else -> (if (pages.isEmpty()) 0 else 1).also { pages.add(key) }
    }
    if (dir != 0) for (v in listOf<View>(settingsList, findViewById(R.id.settingsTitle), findViewById(R.id.settingsCrumb))) {
        v.translationX = dp(32) * dir.toFloat()
        v.alpha = 0f
        v.animate().translationX(0f).alpha(1f).setDuration(200).setInterpolator(DECELERATE).start()
    }
    findViewById<TextView>(R.id.settingsTitle).text = title
    findViewById<TextView>(R.id.settingsCrumb).apply {
        text = crumb?.let { "$it  ›" }
        visibility = if (crumb == null) View.GONE else View.VISIBLE
    }
    findViewById<TextView>(R.id.settingsHelp).setLines(if (back == null) 2 else 3)
    showHelp(null)
    settingsList.removeAllViews()
    top?.let { settingsList.addView(it) }
    val first = settingsList.childCount
    rows.forEachIndexed { i, (name, value, action) ->
        val style = styles.getOrNull(i)
        settingsList.addView(settingsRow(name, value, steps.getOrNull(i), style, action),
            LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (style?.header != true) topMargin = dp(4) })
    }
    // Focus wraps: Up from the first row goes to the last, Down from the last to the first. Group titles are skipped.
    (settingsList.parent as View).scrollTo(0, 0)
    if (rows.isEmpty()) return // a page that's all [top], like the PIN boxes, focuses itself
    val added = (first until settingsList.childCount).map { settingsList.getChildAt(it) }
    val focusables = added.filter { it.isFocusable }
    if (focusables.isEmpty()) return
    focusables.first().nextFocusUpId = focusables.last().id
    focusables.last().nextFocusDownId = focusables.first().id
    (added.drop(focus.coerceIn(0, rows.size - 1)).firstOrNull { it.isFocusable } ?: focusables.first()).requestFocus()
}

/** Runs [then] at once when unlocked, otherwise after the right PIN. [back] is where Back goes from the PIN page. */
internal fun MainActivity.unlock(back: (() -> Unit)? = null, then: () -> Unit) {
    if (!locked()) return then()
    if (settingsScrim.visibility != View.VISIBLE) showPanel() // from inside the panel, keep where its focus returns to
    pinPage(getString(R.string.enter_pin), back = back) { pin ->
        val now = SystemClock.elapsedRealtime()
        if (now < pinWaitUntil) return@pinPage getString(R.string.too_many_tries, (pinWaitUntil - now) / 1000 + 1)
        if (!pinMatches(prefs.getString("lock_pin", null)!!, pin)) {
            if (++pinFails < PIN_TRIES) return@pinPage getString(R.string.wrong_pin)
            pinFails = 0
            pinWaitUntil = now + 30_000L
            return@pinPage getString(R.string.too_many_tries, 30)
        }
        pinFails = 0
        unlockedUntil = now + UNLOCKED_MS
        val under = pages.dropLast(1) // the pages below the PIN; if then() opens the panel again, Back still slides left
        closeLauncherSettings()
        then()
        if (settingsScrim.visibility == View.VISIBLE) pages.addAll(0, under)
        null
    }
}

/**
 * Four digit boxes: ▲ ▼ change a digit, ◀ ▶ move between boxes, number keys type, OK enters.
 * [done] gets the PIN and returns null when it's accepted, or a message to show before trying again.
 */
internal fun MainActivity.pinPage(title: String, back: (() -> Unit)?, done: (String) -> String?) {
    val digits = arrayOfNulls<Int>(4)
    var at = 0
    val boxes = List(4) { label("", 24f).apply { gravity = Gravity.CENTER } }
    val status = label("", 13f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
    val row = LinearLayout(this).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER
        isFocusable = true
        boxes.forEach { addView(it, LinearLayout.LayoutParams(dp(48), dp(56)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
    }
    fun draw() = boxes.forEachIndexed { i, b ->
        // Only the box being changed shows its digit.
        b.text = if (digits[i] == null) "" else if (i == at) digits[i].toString() else "•"
        b.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.WHITE)
            if (i == at) setStroke(dp(2), fg)
        }
        if (dark) b.background = rimCard(dp(8), i == at)
    }
    fun submit() {
        val error = done(digits.joinToString("")) ?: return
        digits.fill(null)
        at = 0
        draw()
        status.text = error
        ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, -dp(12).toFloat(), dp(12).toFloat(), -dp(8).toFloat(), dp(8).toFloat(), 0f).setDuration(300).start()
    }
    row.setOnKeyListener { _, code, e ->
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        if (digit == null && code !in PIN_KEYS) return@setOnKeyListener false // Back still leaves
        if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> digits[at] = ((digits[at] ?: 0) + 1) % 10
            KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = ((digits[at] ?: 0) + 9) % 10
            KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] != null) at = minOf(3, at + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (digits.all { it != null }) return@setOnKeyListener true.also { submit() }
                else if (digits[at] != null) at = minOf(3, at + 1)
            else -> {
                digits[at] = digit
                if (at == 3) return@setOnKeyListener true.also { draw(); submit() }
                at++
            }
        }
        draw()
        true
    }
    draw()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(8))
        addView(row)
        addView(status.apply { setPadding(0, dp(12), 0, 0) })
        addView(label(getString(R.string.pin_hint), 12f).apply {
            maxLines = 2
            gravity = Gravity.CENTER
            alpha = 0.6f
            setPadding(0, dp(8), 0, 0)
        })
        if (lockOn()) addView(label(getString(R.string.pin_forgot), 12f).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            maxLines = 3
            setPadding(0, dp(24), 0, 0)
        })
    }
    showPage(title, emptyList(), top = box, back = back)
    pinBox = row
    row.requestFocus()
}

internal fun MainActivity.settingsRow(title: String, value: String, step: ((Int) -> Unit)? = null, style: RowStyle? = null, action: () -> Unit): View {
    if (style?.header == true) return groupTitle(title)
    if (style?.hero == true) return heroRow(title, value, style, action)
    val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    val options = style?.choices
    // Choices that would squeeze the title go under it, across the row.
    val stacked = style?.art != null || options != null && (options.size > 2 || title.length + options.sumOf { it.first.length } > 26)
    val restyle = mutableListOf<(Boolean) -> Unit>()
    val name = label(title, 16f).apply { setTextColor(restInk()); panelShadow() }
    val sub = style?.sub?.let { label(it, 13f).apply { setTextColor(valueInk()); panelShadow() } }
    val iconView = style?.icon?.let { icon(it, 24, iconInk(false)) }
    val line = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        iconView?.let { addView(it, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            sub?.let { addView(it, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(2) }) }
        }, LinearLayout.LayoutParams(0, wrap, 1f))
    }
    val end = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    fun endIcon(res: Int, size: Int) = icon(res, size, valueInk()).also { v ->
        end.addView(v, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(6) })
        restyle += { f -> v.setColorFilter(valueInk(f)) }
    }
    style?.thumb?.let { (choice, forDark) ->
        end.addView(wallpaperThumb(choice, forDark), LinearLayout.LayoutParams(dp(80), dp(45)))
    }
    var below: View? = null
    when {
        options != null -> {
            val (view, paint) = if (style.art != null) artChoices(style.art, options, value) else choices(options, value, stacked, style.dim)
            restyle += paint
            if (stacked) below = view else end.addView(view)
        }
        value == TOGGLE_ON || value == TOGGLE_OFF -> {
            val (view, paint) = switch(value == TOGGLE_ON)
            restyle += paint
            end.addView(view, LinearLayout.LayoutParams(dp(44), dp(24)))
        }
        value == CHOSEN -> endIcon(R.drawable.ms_check, 22)
        else -> {
            val opens = value.endsWith("›") // a row that opens a page ends in a chevron, not the "›" character
            val text = value.removeSuffix("›").trim()
            if (text.isNotEmpty()) {
                val shown = label(text, 15f).apply { setTextColor(valueInk()); panelShadow() }
                end.addView(shown, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(16) })
                restyle += { f ->
                    shown.setTextColor(valueInk(f))
                    // A row stepped with Left and Right shows its arrows while focused.
                    if (step != null) shown.setCompoundDrawablesRelative(if (f) arrow(R.drawable.ms_chevron_left, valueInk(f)) else null, null,
                        if (f) arrow(R.drawable.ms_chevron_right, valueInk(f)) else null, null)
                }
            }
            if (opens) endIcon(R.drawable.ms_chevron_right, 22)
        }
    }
    if (style?.ext == true) endIcon(R.drawable.ms_open_in_new, 18)
    if (end.childCount > 0) line.addView(end, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(12) })
    return LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(if (stacked) 12 else 6), dp(16), dp(if (stacked) 14 else 6))
        minimumHeight = dp(if (sub != null) 68 else 56)
        addView(line, LinearLayout.LayoutParams(MATCH_PARENT, wrap))
        below?.let { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, wrap).apply { topMargin = dp(10); marginStart = if (iconView != null) dp(40) else 0 }) }
        setOnClickListener { action() }
        panelFocus(this, 12, style?.help, style?.area) { focused ->
            // The title glides to bright rather than snapping, so moving through the list feels smooth.
            ValueAnimator.ofArgb(name.currentTextColor, if (focused) focusInk() else restInk()).apply {
                duration = 160
                interpolator = DECELERATE
                addUpdateListener { a -> name.setTextColor(a.animatedValue as Int) }
            }.start()
            if (style?.logo == null) iconView?.let { v -> style?.icon?.let { v.setImageResource(if (focused) filled(it) else it) }; v.setColorFilter(iconInk(focused)) }
            sub?.setTextColor(valueInk(focused))
            restyle.forEach { it(focused) }
        }
        tag = step // dispatchKeyEvent calls it for Left (-1) and Right (+1)
    }
}

internal fun MainActivity.showBackup() {
    showPage(getString(R.string.backup), listOf(
        page(getString(R.string.save_settings), "") {
            unlock(back = { showBackup() }) {
                if (settingsScrim.visibility != View.VISIBLE) showPanel()
                transferSettings(false)
            }
        },
        page(getString(R.string.load_settings), "") { confirmLoadSettings() },
        page(getString(R.string.backup_tmdb_key), getString(if (settings.backupSecrets) R.string.on else R.string.off)) {
            settings.backupSecrets = !settings.backupSecrets
            showBackup()
        },
    ), top = label(getString(R.string.help_settings_transfer) + "\n" + getString(R.string.backup_key_warning), 14f).apply { maxLines = 8; ellipsize = null }) {
        renderSettings(R.string.backup)
    }
}

internal fun MainActivity.transferSettings(load: Boolean) {
    if (transferringSettings) return
    // A backup left by an earlier install or by ATV Launcher can only be read once the TV has been allowed to read files (Android 8 to 12).
    if (load && Build.VERSION.SDK_INT <= 32 && checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), 41)
        toast(getString(R.string.backup_allow_files), long = true)
        return
    }
    transferringSettings = true
    val snapshot = prefs.all
    io.execute {
        val result = runCatching {
            if (load) {
                val values = SettingsTransfer.loadShared(this)
                // The TMDB key and the Watch Next sign-ins live in files of their own, not in the preferences.
                SettingsTransfer.restore(prefs, values - "tmdb_key" - "wn_secrets")
                (values["tmdb_key"] as? String)?.let { Tmdb.saveKey(this, it) }
                (values["wn_secrets"] as? String)?.let { WatchNext.importJson(this, it) }
                SettingsTransfer.where(this)
            } else {
                // Only when switched on: the keys and sign-ins are then plain text in the backup file.
                val extra = HashMap<String, Any?>()
                if (settings.backupSecrets) {
                    Tmdb.key(this)?.let { extra["tmdb_key"] = it }
                    WatchNext.exportJson(this)?.let { extra["wn_secrets"] = it }
                }
                SettingsTransfer.saveShared(this, snapshot + extra)
            }
        }
        ui.post {
            transferringSettings = false
            if (isDestroyed) return@post
            result.onSuccess { path ->
                if (load) {
                    prefs.edit().remove("reopen_settings").remove("return_settings").remove("return_dream").apply()
                    recreate()
                } else showPage(getString(R.string.save_settings), listOf(
                    page(getString(R.string.done), "") { showBackup() },
                ), top = label(getString(R.string.settings_saved, path), 14f).apply { maxLines = 12; ellipsize = null }) {
                    showBackup()
                }
            }.onFailure { e ->
                android.util.Log.w("Nooko Launcher", "Settings transfer failed", e)
                toast(getString(R.string.settings_transfer_failed), long = true)
            }
        }
    }
}

/** What's new in this version (the CHANGELOG.md section the build put in), with check or install on top. */
internal fun MainActivity.showUpdates() {
    prefs.edit().putString("seen_notes", myVersion()).apply()
    updateBadge()
    val ver = availableUpdate()
    showPage(getString(R.string.whats_new_title, myVersion()), listOf(
        if (ver != null) page(getString(R.string.install_update, ver)) { installUpdate(ver) }
        else page(getString(R.string.check_updates)) { checkUpdate(force = true) },
    ), back = { renderSettings(R.string.updates) })
    // Focusable, lit on focus, so Down walks through notes longer than the panel and it scrolls along.
    getString(R.string.whats_new).split('\n').forEach {
        if (it.startsWith("#")) {
            settingsList.addView(label(it.substring(1), 13f).apply {
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(18), dp(14), dp(2))
            })
            return@forEach
        }
        settingsList.addView(label("", 13f).apply {
            text = noteText(it)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            alpha = 0.7f
            isFocusable = true
            setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
            setPadding(dp(14), dp(10), dp(14), dp(2))
        })
    }
    settingsList.getChildAt(0).nextFocusDownId = View.NO_ID // showPage stops Down at its last row
    settingsList.getChildAt(settingsList.childCount - 1).let { it.id = View.generateViewId(); it.nextFocusDownId = it.id }
}

internal fun MainActivity.installUpdate(ver: String) {
    if (updating) return
    if (!packageManager.canRequestPackageInstalls()) {
        toast(getString(R.string.allow_installs), long = true, system = true)
        return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
    }
    updating = true
    renderSettings(R.string.updates)
    val installer = packageManager.packageInstaller
    val apk = File(cacheDir, ApkProvider.NAME)
    io.execute {
        val ok = try {
            apk.delete()
            val conn = URL("https://github.com/$REPO/releases/download/v$ver/Nooko-Launcher.apk").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                    }
                }
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)
            // Android 12+ can skip the confirm box when we're the app's installer; otherwise Android asks.
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                try {
                    apk.inputStream().use { input ->
                        session.openWrite("Nooko-Launcher.apk", 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                } catch (e: Exception) {
                    session.abandon()
                    throw e
                }
                val done = PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java).setAction(UPDATE_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(done.intentSender)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("Nooko Launcher", "update download failed ($e)")
            apk.delete()
            false
        }
        ui.post {
            if (isDestroyed) return@post
            if (!ok) {
                updating = false
                toast(getString(R.string.update_download_failed), long = true)
            }
            if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
        }
    }
}
