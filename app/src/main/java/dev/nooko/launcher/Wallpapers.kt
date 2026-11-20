package dev.nooko.launcher

import dev.nooko.launcher.MainActivity.Companion.AREA_ALL
import dev.nooko.launcher.MainActivity.Companion.CHOSEN
import dev.nooko.launcher.MainActivity.Companion.DARK_BG
import dev.nooko.launcher.MainActivity.Companion.DECELERATE
import dev.nooko.launcher.MainActivity.Companion.MEDIA_REQUEST
import dev.nooko.launcher.MainActivity.Companion.PHOTO_EVERY
import dev.nooko.launcher.MainActivity.Companion.READ_PHOTOS
import dev.nooko.launcher.MainActivity.Companion.WALLPAPER_CARD

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
 * The wallpaper: loading it, the pictures to choose from (your photos, Bing, Reddit), the photo folders and the phone upload, as
 * extensions of [MainActivity]. The views and the state stay in MainActivity; what they do lives here.
 */
internal fun MainActivity.supportsUhdWallpaper(): Boolean {
    // shortcut: Hisense lushan captures the 4K layer but displays it black; remove when panel output is verified.
    if (Build.MANUFACTURER.equals("Hisense", ignoreCase = true) && Build.DEVICE == "lushan") return false
    val mode = if (Build.VERSION.SDK_INT >= 30) display?.mode else windowManager.defaultDisplay.mode
    if (mode == null || mode.physicalWidth < UHD_WIDTH || mode.physicalHeight < UHD_HEIGHT) return false
    val memory = getSystemService(android.app.ActivityManager::class.java)
    return !memory.isLowRamDevice && memory.memoryClass >= 192
}

internal fun MainActivity.loadWallpaper() {
    val view = findViewById<ImageView>(R.id.wallpaper)
    val choice = currentWallpaper()
    if (resumed && choice == Bing.CHOICE) Bing.refresh(applicationContext, prefs)
    if (resumed && choice == Reddit.CHOICE) Reddit.refresh(applicationContext, prefs)
    val slideshow = choice.startsWith("folder:")
    val load = ++wallpaperLoad
    view.alpha = 1f
    if (choice.startsWith("plain:")) { // the root's ground colour shows through
        photoWallpaper.clear()
        view.setImageDrawable(null)
        glassPixels = null
        applyGlass() // open menus and the panel lose their frost too
        wpThumb = null
        wpFrames = emptyList()
        fitInk()
        return
    }
    val allowUhd = supportsUhdWallpaper()
    io.execute {
        val photo = if (slideshow) runCatching { folderPhoto(choice) }.getOrNull() else null
        fun decode(uhd: Boolean) = when {
            slideshow -> photo?.let { decodeMediaPhoto(it, uhd) }
            else -> decodeWallpaper(choice, uhd)
        }
        val bmp = try {
            decode(allowUhd)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            if (allowUhd) runCatching { decode(false) }.getOrNull() else null
        }
        if (slideshow) {
            if (bmp == null) {
                folderPending = true
                ui.post { scheduleFolderRetry() }
            } else {
                folderPending = false
                folderRetries = 0
                ui.post { ui.removeCallbacks(folderRetryTask) }
            }
        } else {
            folderPending = false
            folderRetries = 0
            ui.post { ui.removeCallbacks(folderRetryTask) }
        }
        // Kept even with glass off, so turning it on needs no second decode.
        val screen = bmp?.let { glassScreen(it, ground) }
        val sharp = if (bmp != null && glassStyle() == GlassStyle.CLEAR) glassSharp(bmp, ground) else null
        val thumb = bmp?.let { Bitmap.createScaledBitmap(it, 64, maxOf(1, 64 * it.height / it.width), true) }
        // Nothing reads the pixels from here on: keep the picture in graphics memory only, not once more in the process.
        val shown = bmp?.let { b ->
            try {
                b.copy(Bitmap.Config.HARDWARE, false)?.also { b.recycle() } ?: b
            } catch (e: Exception) {
                b
            } catch (_: OutOfMemoryError) {
                b
            }
        }
        ui.post {
            if (isDestroyed || load != wallpaperLoad) return@post
            if (photo != null && bmp != null && photo != prefs.getLong(photoKey(), -1)) {
                prefs.edit().putLong(photoKey(), photo).apply()
            }
            wpThumb = thumb
            wpFrames = listOfNotNull(thumb)
            // The glass is tinted with the wallpaper's mean colour; kept, so the next start has it before the picture.
            thumb?.let { t -> prefs.edit().putInt("glass_hue", meanColour(IntArray(t.width * t.height).also { t.getPixels(it, 0, t.width, 0, 0, t.width, t.height) })).apply() }
            if (wantDark() != dark && !wallpaperPickerOpen()) return@post recreate()
            fitInk()
            if (allowUhd && shown?.width == UHD_WIDTH && shown.height == UHD_HEIGHT) {
                glassPixels = screen
                sharpPixels = sharp
                applyGlass()
                photoWallpaper.show(shown) { drewUhd ->
                    if (load == wallpaperLoad && !isDestroyed) view.setImageBitmap(if (drewUhd) null else shown)
                }
                return@post
            }
            photoWallpaper.clear()
            val old = view.drawable
            if (old == null || shown == null) {
                glassPixels = screen
                sharpPixels = sharp
                applyGlass()
                view.setImageBitmap(shown)
                return@post
            }
            // Soft change from one photo to the next; then drop the old one so only one stays in memory.
            val fade = TransitionDrawable(arrayOf(old, BitmapDrawable(resources, shown))).apply { isCrossFadeEnabled = true }
            view.setImageDrawable(fade)
            fade.startTransition(800)
            ui.postDelayed({
                if (isDestroyed || load != wallpaperLoad) return@postDelayed
                if (view.drawable === fade) view.setImageBitmap(shown)
                glassPixels = screen
                sharpPixels = sharp
                applyGlass() // the frost changes with the picture, when the crossfade lands
            }, 900)
        }
    }
}

internal fun MainActivity.scheduleFolderRetry() {
    if (folderRetries >= 5 || isDestroyed) return
    val delay = when (folderRetries++) {
        0 -> 2_000L
        1 -> 4_000L
        2 -> 8_000L
        else -> 15_000L
    }
    ui.removeCallbacks(folderRetryTask)
    ui.postDelayed(folderRetryTask, delay)
}

internal fun MainActivity.decodeWallpaper(choice: String, allowUhd: Boolean = false): Bitmap? {
    if (choice.startsWith("plain:")) return null
    return when {
        choice == Bing.CHOICE -> fitWallpaper(allowUhd) { Bing.file(this).inputStream() }
        choice == Reddit.CHOICE -> fitWallpaper(allowUhd) { Reddit.file(this).inputStream() }
        choice.startsWith("photo:") -> decodeMediaPhoto(choice.substringAfter(':').toLongOrNull() ?: return null, allowUhd)
        else -> {
            val file = File(wallpaperDir(), choice.removePrefix("file:"))
            fitWallpaper(allowUhd) { file.inputStream() }
        }
    }
}

internal fun MainActivity.pickWallpaper(forDark: Boolean, focusOn: String? = null) {
    lastSettings = R.string.wallpaper
    val current = currentWallpaper(forDark)
    val active = getString(R.string.wall_in_use)
    val summary = LinearLayout(this).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(wallpaperThumb(current, forDark, true), LinearLayout.LayoutParams(dp(104), dp(59)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(label(active, 12f).apply { setTextColor(valueInk()); panelShadow() })
            addView(label(wallpaperName(current), 17f).apply {
                setTextColor(focusInk())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                panelShadow()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (current.startsWith("folder:")) {
                val interval = getString(PHOTO_EVERY.getValue(photoEvery()))
                val count = if (current.startsWith("folder:")) savedPhotoSelection().size else 0
                val detail = if (count > 0) {
                    resources.getQuantityString(R.plurals.photo_count, count, count) + " · " + interval
                } else interval
                addView(label(detail, 12f).apply { setTextColor(valueInk()); panelShadow() })
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ms_chevron_right, 20, iconInk(false)))
        setOnClickListener { pickActiveWallpaper(forDark) }
        val idle = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(ink(.06f)) }
        panelFocus(this, 12, null, AREA_ALL) { if (!it) background = idle }
        background = idle
    }
    val categories = listOf(
        Tile(getString(R.string.wall_my_photos), cover = R.drawable.wall_cover_photos, captionIcon = R.drawable.ms_photo_library) { pickMyPhotos(forDark) },
        Tile(getString(R.string.wall_automatic), cover = R.drawable.wall_cover_automatic, captionIcon = R.drawable.ms_shuffle) { pickAutomaticWallpaper(forDark) },
    )
    val entry = when (focusOn) {
        getString(R.string.my_uploads), getString(R.string.choose_from_tv) -> getString(R.string.wall_my_photos)
        else -> focusOn ?: getString(R.string.wall_my_photos)
    }
    wallGrid(getString(R.string.wallpaper), listOf(R.string.wall_browse to categories), forDark, "", entry,
        listOf(active to summary), columns = 2, tileHeight = 86) { renderSettings(WALLPAPER_CARD) }
}

internal fun MainActivity.pickActiveWallpaper(forDark: Boolean) {
    val current = currentWallpaper(forDark)
    val back = { pickWallpaper(forDark, getString(R.string.wall_in_use)) }
    val again = { pickActiveWallpaper(forDark) }
    val rows = mutableListOf<Triple<String, String, () -> Unit>>()
    if (current.startsWith("folder:")) {
        rows += page(getString(R.string.wall_edit_photos)) { openPhotoFolder(forDark, current, again, edit = true) }
    }
    if (current.startsWith("folder:")) {
        rows += page(getString(R.string.folder_photo_changes), getString(PHOTO_EVERY.getValue(photoEvery()))) { pickPhotoEvery(again) }
    }
    if (current.startsWith("folder:")) {
        rows += page(getString(R.string.wall_shuffle_now)) { nextPhoto(); scheduleWallpaperTick(); again() }
    }
    if (current == Reddit.CHOICE) {
        rows += page(getString(R.string.reddit_new)) { Reddit.refresh(applicationContext, prefs, force = true); again() }
    }
    val preview = wallpaperThumb(current, forDark, true)
    val controls = rows.ifEmpty {
        listOf(page(getString(R.string.wall_preview)) { previewWallpaper(current, again) })
    }
    val styles = controls.map { (title) ->
        RowStyle(icon = when (title) {
            getString(R.string.wall_edit_photos) -> R.drawable.ms_photo_library
            getString(R.string.folder_photo_changes) -> R.drawable.ms_schedule
            getString(R.string.wall_shuffle_now) -> R.drawable.ms_shuffle
            getString(R.string.wp_sources) -> R.drawable.ms_filter_list
            getString(R.string.tmdb_artwork) -> R.drawable.ms_landscape
            else -> R.drawable.ms_play_circle
        })
    }
    showPage(wallpaperName(current), controls, top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, dp(145)))
    }, styles = styles, crumb = getString(R.string.wallpaper), back = back)
}

internal fun MainActivity.pickAutomaticWallpaper(forDark: Boolean, focusOn: String? = null) {
    val tiles = listOf(Bing.CHOICE, Reddit.CHOICE).map { choice ->
        Tile(wallpaperName(choice), choice) {
            setWallpaper(forDark, choice)
            previewWallpaper(choice) { pickAutomaticWallpaper(forDark, choice) }
        }
    }
    wallGrid(getString(R.string.wall_automatic), listOf(null to tiles), forDark, currentWallpaper(forDark), focusOn,
        columns = 2, tileHeight = 94) { pickWallpaper(forDark, getString(R.string.wall_automatic)) }
}

internal fun MainActivity.pickMyPhotos(forDark: Boolean, focusOn: String? = null) {
    val rows = listOf(
        page(getString(R.string.wall_tv_photos)) { browsePhotoFolders(forDark) },
        page(getString(R.string.my_uploads)) { pickUploads(forDark) },
        page(getString(R.string.add_from_phone)) {
            uploadWallpaper(forDark, applyPhoto = false) { pickUploads(forDark, getString(R.string.add_from_phone)) }
        },
    )
    val styles = listOf(
        RowStyle(icon = R.drawable.ms_folder, sub = getString(R.string.wall_tv_photos_detail)),
        RowStyle(icon = R.drawable.ms_photo_library, sub = getString(R.string.help_my_uploads)),
        RowStyle(icon = R.drawable.ms_add_photo_alternate, sub = getString(R.string.wall_phone_detail)),
    )
    showPage(getString(R.string.wall_my_photos), rows, focus = rows.indexOfFirst { it.first == focusOn },
        styles = styles, crumb = getString(R.string.wallpaper)) {
        pickWallpaper(forDark, getString(R.string.wall_my_photos))
    }
}

/** Focus [focusOn] by choice or label. If it is gone, focus the active wallpaper. */
internal fun MainActivity.wallGrid(title: String, groups: List<Pair<Int?, List<Tile>>>, forDark: Boolean, current: String, focusOn: String?,
                     extras: List<Pair<String, View>> = emptyList(), columns: Int = 3, tileHeight: Int = 62,
                     footer: List<View> = emptyList(), crumb: String = getString(R.string.atv_settings), back: () -> Unit) {
    var focus = extras.firstOrNull { it.first == focusOn }?.second
    var selected: View? = null
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        extras.forEach { (_, view) -> addView(view) }
        for ((name, tiles) in groups) {
            name?.let {
                addView(groupTitle(getString(it)).apply { setPadding(dp(16), dp(10), dp(16), dp(2)) })
            }
            for (i in tiles.indices step columns) tiles.subList(i, minOf(i + columns, tiles.size)).let { line ->
                addView(LinearLayout(context).apply {
                    line.forEach { t ->
                        val chosen = t.checked || (t.choice != null && t.choice == current)
                        val help = t.help ?: if (t.icon == null && t.captionIcon == null) R.string.help_wp_tile else null
                        val v = wallpaperTile(t, forDark, chosen, help, tileHeight)
                        if (focusOn != null && focus == null && (t.choice == focusOn || t.name == focusOn)) focus = v
                        if (chosen && selected == null) selected = v
                        addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    repeat(columns - line.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
                })
            }
        }
        footer.forEach { addView(it) }
    }
    showPage(title, emptyList(), top = grid, crumb = crumb) { back() }
    findViewById<TextView>(R.id.settingsHelp).setLines(2)
    (focus ?: selected ?: grid.getFocusables(View.FOCUS_DOWN).firstOrNull())?.requestFocus()
}

/** Your uploads: OK uses one; the last tile removes a photo. */
internal fun MainActivity.pickUploads(forDark: Boolean, focusOn: String? = null) {
    val current = currentWallpaper(forDark)
    val photos = wallpaperChoices(forDark).filter { it.second.startsWith("file:") }
    val tiles = ownUploads(forDark).map { choice ->
        Tile(wallpaperName(choice), choice) {
            previewPhotoChoice(forDark, choice, listOf(choice)) { pickUploads(forDark, choice) }
        }
    } + listOf(Tile(getString(R.string.add_from_phone), icon = R.drawable.ms_add_photo_alternate) {
        uploadWallpaper(forDark, applyPhoto = false) { pickUploads(forDark, getString(R.string.add_from_phone)) }
    }) +
        listOfNotNull(
            if (photos.isEmpty()) null else Tile(getString(R.string.remove_photo_row), icon = R.drawable.ms_delete) { removeWallpaper(photos, forDark) },
        )
    wallGrid(getString(R.string.my_uploads), listOf(null to tiles), forDark, current, focusOn) {
        pickMyPhotos(forDark, getString(R.string.my_uploads))
    }
}

/** One wallpaper in the grid: its picture (or an icon for an action), its name, and a check on the one in use. */
internal fun MainActivity.wallpaperTile(tile: Tile, forDark: Boolean, chosen: Boolean, help: Int?, height: Int): View {
    val pic: View = if (tile.cover != null) ImageView(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(ink(.08f)) }
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(tile.cover)
    } else if (tile.choice != null) wallpaperThumb(tile.choice, forDark, big = height > 62) else FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(ink(.07f)); setStroke(1, ink(.14f)) }
        addView(icon(tile.icon!!, 26, valueInk()), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    }
    val frame = FrameLayout(this).apply {
        addView(pic, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (tile.hideLabel) alpha = .87f
        if (chosen) addView(icon(R.drawable.ms_check, 18, Color.WHITE).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(0xD9232228.toInt())
            }
        }, FrameLayout.LayoutParams(dp(24), dp(22), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(6), dp(6))
        })
    }
    val name = label(tile.name, if (height > 70) 15f else 13f).apply {
        maxLines = if (tile.captionIcon != null || tile.detail != null) 1 else 2
        ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(restInk())
        panelShadow()
    }
    val smallIcon = tile.captionIcon?.let { icon(it, 16, iconInk(false)) }
    return LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.VERTICAL
        if (tile.hideLabel) clipToPadding = false
        setPadding(dp(6), dp(5), dp(6), dp(if (tile.hideLabel) 5 else 8))
        addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, dp(height)))
        if (tile.hideLabel) {
            contentDescription = if (chosen) getString(R.string.wall_checked, tile.name) else tile.name
        } else {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                smallIcon?.let {
                    addView(it, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
                }
                addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                tile.detail?.let {
                    addView(label(it, 12f).apply {
                        setTextColor(valueInk())
                        setPadding(dp(6), 0, 0, 0)
                        panelShadow()
                    })
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7) })
        }
        setOnClickListener { tile.action() }
        panelFocus(this, 12, help?.let { getString(it) }, AREA_ALL) { focused ->
            name.setTextColor(if (focused) focusInk() else restInk())
            smallIcon?.setColorFilter(iconInk(focused))
            if (tile.hideLabel) {
                background = null
                frame.animate().cancel()
                val scale = if (focused) 1.06f else 1f
                frame.animate().scaleX(scale).scaleY(scale).alpha(if (focused) 1f else .87f)
                    .setDuration(if (animationsOn()) 120 else 0).start()
                translationZ = if (focused) dp(5).toFloat() else 0f
            }
        }
        // The panel turns Left and Right into a row's step; in the grid they move between pictures.
        tag = { d: Int -> focusSearch(if (d < 0) View.FOCUS_LEFT else View.FOCUS_RIGHT)?.requestFocus(); Unit }
    }
}

internal fun MainActivity.previewWallpaper(choice: String, back: (() -> Unit)? = null) {
    wallPreview = choice
    previewBack = back
    settingsScrim.animate().alpha(0f).setDuration(220).setInterpolator(DECELERATE).start()
    toast(getString(R.string.wp_preview_hint))
}

internal fun MainActivity.endWallPreview() {
    val choice = wallPreview ?: return
    wallPreview = null
    settingsScrim.animate().alpha(1f).setDuration(220).setInterpolator(DECELERATE).start()
    previewBack?.invoke() ?: pickWallpaper(dark, choice)
    previewBack = null
}

internal fun MainActivity.previewPhotoChoice(forDark: Boolean, choice: String, photos: List<String>, selected: Set<Long> = emptySet(), back: () -> Unit) {
    if (photos.isEmpty() || photoPreview != null) return
    val root = findViewById<FrameLayout>(R.id.root)
    val layer = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    root.addView(layer, root.indexOfChild(findViewById<View>(R.id.content)), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    val controls = label("", 15f).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(0xE6222026.toInt()); cornerRadius = dp(12).toFloat() }
        setPadding(dp(20), dp(10), dp(20), dp(10))
        visibility = View.INVISIBLE
    }
    root.addView(controls, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        bottomMargin = dp(24)
    })
    val preview = PhotoPreview(layer, controls, photos, { shown ->
        val id = shown.takeIf { it.startsWith("photo:") }?.substringAfter(':')?.toLongOrNull()
        setWallpaper(forDark, choice, selected, id.takeIf { choice.startsWith("folder:") })
    }, back)
    photoPreview = preview
    showPhotoPreview(preview)
}

internal fun MainActivity.showPhotoPreview(preview: PhotoPreview) {
    val photo = preview.choices[preview.index]
    val generation = ++preview.generation
    preview.shown = false
    if (preview.layer.visibility == View.VISIBLE) preview.controls.text = getString(R.string.wall_loading_photo)
    io.execute {
        val color = if (photo.startsWith("plain:")) if (dark) DARK_BG else 0xFFF3F5F7.toInt() else null
        val bitmap = if (color == null) runCatching { decodeWallpaper(photo) }.getOrNull() else null
        ui.post {
            if (isDestroyed || photoPreview !== preview || generation != preview.generation) return@post
            if (bitmap == null && color == null) {
                finishPhotoPreview(false)
                toast(getString(R.string.wall_photo_unavailable), long = true)
                return@post
            }
            preview.layer.setBackgroundColor(color ?: Color.BLACK)
            preview.layer.setImageBitmap(bitmap)
            preview.layer.visibility = View.VISIBLE
            preview.controls.text = if (preview.choices.size == 1) getString(R.string.wall_preview_apply)
                else getString(R.string.wall_preview_apply_multiple, preview.index + 1, preview.choices.size)
            preview.controls.visibility = View.VISIBLE
            preview.shown = true
            settingsScrim.animate().alpha(0f).setDuration(180).start()
        }
    }
}

internal fun MainActivity.finishPhotoPreview(apply: Boolean, returnToPicker: Boolean = true) {
    val preview = photoPreview ?: return
    photoPreview = null
    val root = findViewById<FrameLayout>(R.id.root)
    root.removeView(preview.layer)
    root.removeView(preview.controls)
    settingsScrim.animate().alpha(1f).setDuration(180).start()
    if (apply) preview.apply(preview.choices[preview.index])
    if (returnToPicker) preview.back()
}

internal fun MainActivity.setWallpaper(forDark: Boolean, choice: String, selected: Set<Long> = emptySet(), firstPhoto: Long? = null) {
    prefs.edit().putString("wp_dark", choice).putString("wp_light", choice)
        .putLong(photoAtKey(), System.currentTimeMillis()).apply {
            if (firstPhoto == null) remove(photoKey()) else putLong(photoKey(), firstPhoto)
            remove("wp_previous_photo")
            if (selected.isEmpty()) remove("wp_selected_photos")
            else putStringSet("wp_selected_photos", selected.mapTo(mutableSetOf()) { it.toString() })
        }.apply()
    io.execute { deck.clear() }
    scheduleWallpaperTick()
    Bing.dropIfUnused(applicationContext, prefs) // choosing another wallpaper deletes the Bing picture at once
    if (choice == Bing.CHOICE) Bing.refresh(applicationContext, prefs, force = true)
    Reddit.dropIfUnused(applicationContext, prefs)
    if (choice == Reddit.CHOICE) Reddit.refresh(applicationContext, prefs, force = true)
    loadWallpaper()
}

internal fun MainActivity.scheduleWallpaperTick() {
    ui.removeCallbacks(wallpaperTick)
    ui.post(wallpaperTick)
}

internal fun MainActivity.pickPhotoEvery(back: () -> Unit = { pickWallpaper(dark, getString(R.string.folder_photo_changes)) }) {
    val rows = PHOTO_EVERY.map { (minutes, name) ->
        page(getString(name), if (minutes == photoEvery()) CHOSEN else "") {
            prefs.edit().putInt("wp_every", minutes).apply()
            scheduleWallpaperTick()
            back()
        }
    }
    showPage(getString(R.string.folder_photo_changes), rows, PHOTO_EVERY.keys.indexOf(photoEvery()), back = back)
}

internal fun MainActivity.nextPhoto() {
    val previous = prefs.getLong(photoKey(), -1)
    prefs.edit().remove(photoKey()).putLong("wp_previous_photo", previous).putLong(photoAtKey(), System.currentTimeMillis()).apply()
    loadWallpaper()
}

/** The folder's photo on show now, or the next one in this round's shuffle. Runs on the io thread. */
internal fun MainActivity.folderPhoto(choice: String): Long? {
    val ids = folderPhotos(contentResolver, choice)
    val selected = savedPhotoSelection()
    val eligible = if (selected.isEmpty()) ids else ids.filter { it in selected }
    val saved = prefs.getLong(photoKey(), -1).takeIf { it >= 0 }
    val previous = prefs.getLong("wp_previous_photo", -1)
    val picked = pickFolderPhoto(eligible, saved, deck, previous)
    if (picked != null) deck.remove(picked)
    return picked
}

/** Every folder with photos in it, internal storage and USB drives alike: (bucket id, name, photo count). */
internal fun MainActivity.photoFolders(): List<Triple<String, String, Int>> {
    val found = LinkedHashMap<String, Pair<String, Int>>()
    contentResolver.query(PHOTOS, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
        null, null, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            found[id] = (c.getString(1) ?: "Photos") to (found[id]?.second ?: 0) + 1
        }
    }
    return found.map { (id, v) -> Triple(id, v.first, v.second) }
}

internal fun MainActivity.decodeMediaPhoto(id: Long, allowUhd: Boolean = false): Bitmap? {
    val uri = ContentUris.withAppendedId(PHOTOS, id)
    return fitWallpaper(allowUhd) { contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") }
}

internal fun MainActivity.mediaPhotoThumb(id: Long, big: Boolean): Bitmap? {
    val uri = ContentUris.withAppendedId(PHOTOS, id)
    val size = if (big) android.util.Size(dp(186), dp(105)) else android.util.Size(dp(112), dp(68))
    if (Build.VERSION.SDK_INT >= 29) return contentResolver.loadThumbnail(uri, size, null)
    @Suppress("DEPRECATION")
    return MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
}

internal fun MainActivity.browsePhotoFolders(forDark: Boolean, focusOn: String? = null) {
    val back = { pickMyPhotos(forDark, getString(R.string.wall_tv_photos)) }
    if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
        afterPhotoAccess = { browsePhotoFolders(forDark, focusOn) }
        return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
    }
    val title = getString(R.string.choose_folder)
    var active = true
    showPage(title, emptyList(), crumb = getString(R.string.wall_my_photos), back = back)
    pageExit = { active = false }
    io.execute {
        val folders = runCatching { photoFolders() }.getOrDefault(emptyList())
        ui.post {
            if (isDestroyed || !active) return@post
            if (folders.isEmpty()) {
                showPage(title, listOf(page(getString(R.string.add_from_phone)) {
                    uploadWallpaper(forDark, applyPhoto = false) { pickUploads(forDark) }
                }), styles = listOf(RowStyle(sub = getString(R.string.wall_no_folders_hint))),
                    crumb = getString(R.string.wall_my_photos), back = back)
                return@post
            }
            val tiles = folders.map { (id, name, count) ->
                val choice = "folder:$id:$name"
                Tile(name, choice, help = R.string.wall_open_folder_hint, captionIcon = R.drawable.ms_folder,
                    detail = count.toString()) {
                    openPhotoFolder(forDark, choice, { browsePhotoFolders(forDark, choice) })
                }
            }
            wallGrid(title, listOf(null to tiles), forDark, "", focusOn, columns = 2, tileHeight = 86,
                crumb = getString(R.string.wall_my_photos), back = back)
        }
    }
}

internal fun MainActivity.openPhotoFolder(forDark: Boolean, choice: String, back: () -> Unit, edit: Boolean = false) {
    if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
        afterPhotoAccess = { openPhotoFolder(forDark, choice, back, edit) }
        return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
    }
    val title = choice.substringAfter(':').substringAfter(':')
    var active = true
    showPage(title, emptyList(), crumb = getString(R.string.wall_my_photos), back = back)
    pageExit = { active = false }
    io.execute {
        val ids = runCatching { folderPhotos(contentResolver, choice) }.getOrDefault(emptyList())
        ui.post {
            if (isDestroyed || !active) return@post
            if (ids.isEmpty()) {
                toast(getString(R.string.wall_no_folders_hint), long = true)
                back()
                return@post
            }
            val selected = if (edit) savedPhotoSelection().intersect(ids.toSet()).toMutableSet() else mutableSetOf()
            showPhotoFolder(forDark, choice, title, ids, selected, edit, 0, null, back)
        }
    }
}

internal fun MainActivity.showPhotoFolder(forDark: Boolean, choice: String, title: String, ids: List<Long>, selected: MutableSet<Long>,
                            selecting: Boolean, index: Int, focusOn: String?, back: () -> Unit) {
    val pageSize = 9
    val photoHeight = 63
    val start = index * pageSize
    val visible = ids.subList(start, minOf(ids.size, start + pageSize))
    fun redraw(page: Int = index, focus: String? = focusOn, select: Boolean = selecting) =
        showPhotoFolder(forDark, choice, title, ids, selected, select, page, focus, back)
    val selectLabel = getString(R.string.wall_select_photos)
    val wholeLabel = getString(R.string.wall_whole_folder)
    fun control(name: String, symbol: Int, primary: Boolean = false, action: () -> Unit): View {
        val tint = if (primary) focusInk() else restInk()
        val surface = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(ink(if (primary) .15f else .06f)) }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            gravity = if (primary) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            addView(icon(symbol, 18, tint))
            val title = label(name, if (primary) 15f else 13f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(tint)
                panelShadow()
                setPadding(dp(7), 0, 0, 0)
            }
            addView(title)
            setOnClickListener { action() }
            panelFocus(this, 10, null, AREA_ALL) { focused ->
                if (!focused) background = surface
                title.setTextColor(if (focused) focusInk() else tint)
            }
            background = surface

            tag = { direction: Int ->
                focusSearch(if (direction < 0) View.FOCUS_LEFT else View.FOCUS_RIGHT)?.requestFocus()
                Unit
            }
        }
    }
    val select = if (selecting) {
        label(resources.getQuantityString(R.plurals.wall_selected_count, selected.size, selected.size), 14f).apply {
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(focusInk())
            setPadding(dp(6), 0, 0, 0)
        }
    } else control(selectLabel, R.drawable.ms_checklist) { redraw(focus = "photo:" + visible.first(), select = true) }
    val whole = control(wholeLabel, R.drawable.ms_folder) {
        previewPhotoChoice(forDark, choice, ids.shuffled().map { "photo:$it" }) { redraw(focus = wholeLabel) }
    }
    val controls = LinearLayout(this).apply {
        setPadding(dp(6), 0, dp(6), dp(10))
        addView(select, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
        addView(whole, LinearLayout.LayoutParams(0, dp(38), 1.15f))
    }
    val tiles = visible.mapIndexed { i, id ->
        val photo = "photo:$id"
        Tile(getString(R.string.wall_numbered_photo, start + i + 1), photo,
            help = if (selecting) R.string.wall_select_hint else R.string.wall_preview_hint,
            checked = id in selected, hideLabel = true) {
            if (selecting) {
                if (!selected.add(id)) selected.remove(id)
                redraw(focus = photo)
            } else previewPhotoChoice(forDark, photo, listOf(photo)) { redraw(focus = photo) }
        }
    }
    val footer = mutableListOf<View>()
    if (ids.size > pageSize) {
        val missingRows = (pageSize - visible.size) / 3
        if (missingRows > 0) footer += View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, missingRows * (dp(photoHeight) + 2 * dp(5)))
        }
        val previous = getString(R.string.wall_previous_photos)
        val next = getString(R.string.wall_next_photos)
        val paging = LinearLayout(this).apply { setPadding(dp(6), dp(7), dp(6), 0) }
        if (index > 0) paging.addView(control(previous, R.drawable.ms_chevron_left) {
            val page = index - 1
            redraw(page, "photo:" + ids[page * pageSize])
        }, LinearLayout.LayoutParams(0, dp(33), 1f).apply { marginEnd = dp(6) })
        if (start + pageSize < ids.size) paging.addView(control(next, R.drawable.ms_chevron_right) {
            val page = index + 1
            redraw(page, "photo:" + ids[page * pageSize])
        }, LinearLayout.LayoutParams(0, dp(33), 1f))
        footer += paging
    }
    if (selecting && selected.isNotEmpty()) footer += LinearLayout(this).apply {
        setPadding(dp(6), dp(9), dp(6), 0)
        addView(control(getString(R.string.wall_preview_slideshow), R.drawable.ms_play_circle, primary = true) {
            previewPhotoChoice(forDark, choice, selected.shuffled().map { "photo:$it" }, selected.toSet()) { redraw() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
    }
    val first = "photo:" + visible.first()
    val focus = focusOn ?: first
    val extras = listOf(selectLabel to controls)
    wallGrid(title, listOf(null to tiles), forDark, "", focus.takeUnless { it == wholeLabel }, extras, tileHeight = photoHeight,
        footer = footer, crumb = getString(R.string.wall_my_photos), back = back)
    if (focus == wholeLabel) whole.requestFocus()
}

/** Folders page for wallpapers and the screensaver; [onPick] gets "folder:<bucket id>:<name>". */
internal fun MainActivity.pickFolder(back: () -> Unit, onPick: (String) -> Unit) {
    if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
        afterPhotoAccess = { pickFolder(back, onPick) }
        return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
    }
    val folders = photoFolders()
    if (folders.isEmpty()) {
        return toast(getString(R.string.no_photos), long = true)
    }
    val rows = folders.map { (id, name, n) ->
        page(name, resources.getQuantityString(R.plurals.photo_count, n, n)) { onPick("folder:$id:$name") }
    }
    showPage(getString(R.string.choose_folder), rows, back = back)
}

internal fun MainActivity.removeWallpaper(own: List<Pair<String, String>>, forDark: Boolean, focus: Int = 0) {
    val back = { pickUploads(forDark, getString(R.string.remove_photo_row)) }
    showPage(getString(R.string.remove_photo_title), own.mapIndexed { index, (name, choice) ->
        page(name) {
            showPage(getString(R.string.delete_photo, name), listOf(
                page(getString(R.string.cancel)) { removeWallpaper(own, forDark, index) },
                page(getString(R.string.delete)) {
                    File(wallpaperDir(), choice.removePrefix("file:")).delete()
                    // A theme showing it falls back to its default.
                    prefs.edit().apply {
                        if (prefs.getString("wp_light", null) == choice) remove("wp_light")
                        if (prefs.getString("wp_dark", null) == choice) remove("wp_dark")
                    }.apply()
                    loadWallpaper()
                    back()
                },
            )) { removeWallpaper(own, forDark, index) }
        }
    }, focus, back = back)
}

/**
 * "Add from phone": a QR code for a page served from this TV. The phone posts a photo, we turn it into a
 * wallpaper and show it at once. The server runs only while this panel is open.
 */
/** Add from phone: one QR page that takes a photo (set at once) or an MP4 video (previewed first). */
internal fun MainActivity.uploadWallpaper(forDark: Boolean, applyPhoto: Boolean = true, back: () -> Unit) {
    val ip = lanAddress() ?: return toast(getString(R.string.connect_network), long = true)
    val dir = wallpaperDir() ?: return
    val active = AtomicBoolean(true)
    val status = label(getString(R.string.waiting_photo), 14f).apply { gravity = Gravity.CENTER; maxLines = 2 }
    val server = try {
        UploadServer((1000..9999).random().toString(), File.createTempFile("upload-", ".tmp", cacheDir), t = phoneText()) { file, video ->
            if (!active.get()) return@UploadServer null
            if (video) return@UploadServer null // only photos
            val saved = importWallpaper(file, dir) ?: return@UploadServer null
            ui.post {
                if (isDestroyed || !active.get()) { saved.delete(); return@post }
                if (applyPhoto) setWallpaper(forDark, "file:${saved.name}")
                status.text = getString(R.string.photo_added, saved.nameWithoutExtension)
            }
            saved.nameWithoutExtension
        }
    } catch (e: IOException) {
        return toast(getString(R.string.upload_failed), long = true)
    }
    upload = server
    val url = "http://$ip:${server.port}/${server.code}"
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(12))
        addView(ImageView(this@uploadWallpaper).apply { setImageBitmap(qrBitmap(url, dp(200))) })
        addView(label(url, 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
        addView(label(getString(R.string.scan_hint), 12f).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            maxLines = 3
        })
        addView(status.apply { setPadding(0, dp(12), 0, 0) })
    }
    showPage(getString(R.string.add_from_your_phone), listOf(page(getString(R.string.done), action = back)), top = box, back = back)
    pageExit = {
        active.set(false)
        server.stop()
        if (upload === server) upload = null
    }
}
