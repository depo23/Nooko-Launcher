package dev.nooko.launcher

import dev.nooko.launcher.MainActivity.Companion.AREA_ALL
import dev.nooko.launcher.MainActivity.Companion.CHOSEN

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
 * The settings screens for TMDB and Watch Next, as extensions of [MainActivity]: the region, the streaming services, the Watch Next
 * service, its account and the sign-in with a code. They lived inside MainActivity; here they have a file of their own.
 */
internal fun MainActivity.pickWatchNextService() {
    val chosen = WatchNext.provider(prefs)
    showPage(getString(R.string.wn_service), WatchNext.PROVIDERS.entries.map { (key, name) ->
        page(name, if (key == chosen) CHOSEN else "") {
            settings.watchNextProvider = key
            reloadHero()
            renderSettings(R.string.wn_service)
        }
    }, WatchNext.PROVIDERS.keys.indexOf(chosen), crumb = getString(R.string.tmdb_row)) { renderSettings(R.string.wn_service) }
}

/** What the chosen service needs from you: an API key, a client id, and a sign-in with a code (or an approval on TMDB's site). */
internal fun MainActivity.watchNextAccount() {
    val p = WatchNext.provider(prefs)
    val name = WatchNext.PROVIDERS.getValue(p)
    val back = { renderSettings(R.string.wn_account) }
    fun value(k: String) = WatchNext.get(this, p, k)
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    fun field(title: Int, key: String, secret: Boolean) {
        rows += page(getString(title), getString(if (value(key) != null) R.string.wn_set else R.string.wn_not_set)) {
            fieldPage(getString(title), value(key).orEmpty(), secret, { watchNextAccount() }) { WatchNext.put(this, p, key, it) }
        }
    }
    when (p) {
        "mdblist" -> field(R.string.wn_client_id, "id", false)
        "trakt" -> { field(R.string.wn_client_id, "id", false); field(R.string.wn_client_secret, "secret", true) }
        "simkl" -> field(R.string.wn_client_id, "id", false)
    }
    val canSignIn = when (p) {
        "trakt" -> value("id") != null && value("secret") != null
        "tmdb" -> Tmdb.key(this) != null
        else -> value("id") != null
    }
    if (canSignIn) rows += page(getString(R.string.wn_sign_in)) { signIn(p) }
    if (WatchNext.ready(this, p)) rows += page(getString(R.string.wn_sign_out)) {
        WatchNext.signOut(this, p)
        reloadHero()
        watchNextAccount()
    }
    val help = label(getString(when (p) {
        "trakt" -> R.string.wn_help_trakt
        "simkl" -> R.string.wn_help_simkl
        "mdblist" -> R.string.wn_help_mdblist
        else -> R.string.wn_help_tmdb
    }), 14f).apply { maxLines = 6; setPadding(dp(14), dp(6), dp(14), dp(8)) }
    showPage(name, rows, top = help, crumb = getString(R.string.wn_account), back = back)
}

/** One text to type or paste, kept privately; empty is not kept, and Remove clears it. */
internal fun MainActivity.fieldPage(title: String, current: String, secret: Boolean, back: () -> Unit, save: (String?) -> Unit) {
    lateinit var field: EditText
    fun done(v: String?) {
        if (v != null && v.isEmpty()) return
        save(v)
        if (field.isAttachedToWindow) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        reloadHero()
        back()
    }
    field = textField(current, title, if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0, EditorInfo.IME_ACTION_DONE) { done(it) }
    field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    field.isSaveEnabled = false
    val rows = listOfNotNull(
        page(getString(R.string.done)) { done(field.text.toString().trim()) },
        if (current.isNotEmpty()) page(getString(R.string.wn_remove)) { done(null) } else null,
    )
    showPage(title, rows, top = field.parent as LinearLayout, crumb = getString(R.string.wn_account), back = back)
    settingsList.getChildAt(1).nextFocusUpId = field.id
    field.nextFocusDownId = settingsList.getChildAt(1).id
    field.requestFocus()
}

/** A code to enter on the service's website (or a link to approve), shown with a QR code; the TV waits for you to approve. */
internal fun MainActivity.signIn(p: String) {
    val back = { watchNextAccount() }
    val active = AtomicBoolean(true)
    val status = label(getString(R.string.wn_starting), 14f).apply { maxLines = 5; setPadding(dp(14), dp(10), dp(14), dp(6)) }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(status)
    }
    showPage(WatchNext.PROVIDERS.getValue(p), listOf(page(getString(R.string.cancel)) { back() }), top = top, crumb = getString(R.string.wn_account), back = { back() })
    pageExit = { active.set(false) }
    // A thread of its own: waiting for you must not hold up the TV's other work.
    Thread {
        val device = try { WatchNext.start(this, p) } catch (e: Exception) {
            android.util.Log.w("ATV", "sign-in with $p failed: $e")
            ui.post { if (active.get()) status.text = getString(R.string.wn_failed, e.message.orEmpty()) }
            return@Thread
        }
        ui.post {
            if (!active.get() || isDestroyed) return@post
            top.removeAllViews()
            if (device.userCode.isNotEmpty()) top.addView(label(device.userCode, 30f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14), dp(4), dp(14), dp(2))
            })
            top.addView(label(getString(if (device.userCode.isEmpty()) R.string.wn_visit_approve else R.string.wn_visit, device.url), 13f).apply {
                maxLines = 3
                setPadding(dp(14), dp(2), dp(14), dp(8))
            })
            top.addView(ImageView(this).apply { setImageBitmap(qrBitmap(device.qr, dp(170), rounded = true)) }, LinearLayout.LayoutParams(dp(170), dp(170)))
            status.text = getString(R.string.wn_waiting)
            top.addView(status)
        }
        val end = SystemClock.elapsedRealtime() + device.expiresIn * 1000L
        val every = device.interval.coerceAtLeast(3) * 1000L
        while (active.get() && SystemClock.elapsedRealtime() < end) {
            try { Thread.sleep(every) } catch (e: InterruptedException) { return@Thread }
            if (!active.get()) return@Thread
            when (try { WatchNext.poll(this, p, device) } catch (e: Exception) { WatchNext.Poll.WAIT }) {
                WatchNext.Poll.DONE -> {
                    ui.post { if (active.get() && !isDestroyed) { toast(getString(R.string.wn_signed_in)); reloadHero(); back() } }
                    return@Thread
                }
                WatchNext.Poll.FAIL -> {
                    ui.post { if (active.get()) status.text = getString(R.string.wn_denied) }
                    return@Thread
                }
                WatchNext.Poll.WAIT -> {}
            }
        }
        ui.post { if (active.get() && !isDestroyed) status.text = getString(R.string.wn_expired) }
    }.apply { isDaemon = true }.start()
}

/** The country whose streaming services are shown: the TV's own, or any country TMDB has data for. */
internal fun MainActivity.pickTmdbRegion(focus: Int = 0) {
    val key = Tmdb.key(this) ?: return toast(getString(R.string.tmdb_needs_key), long = true)
    Work.net.execute {
        val list = try { Tmdb.regions(key) } catch (e: Exception) {
            android.util.Log.w("ATV", "tmdb regions failed: $e")
            ui.post { toast(getString(R.string.tmdb_unavailable), long = true) }
            return@execute
        }
        ui.post {
            if (isDestroyed) return@post
            val chosen = settings.tmdbRegion
            val rows = listOf(page(getString(R.string.tmdb_region_device, Locale.getDefault().country.ifEmpty { "US" }), if (chosen == null) CHOSEN else "") {
                settings.tmdbRegion = null
                settingsTmdbChanged()
                renderSettings(R.string.tmdb_region)
            }) + list.map { (code, name) ->
                page(name, if (code == chosen) CHOSEN else code) {
                    settings.tmdbRegion = code
                    settingsTmdbChanged()
                    renderSettings(R.string.tmdb_region)
                }
            }
            val at = list.indexOfFirst { it.first == chosen } + 1
            showPage(getString(R.string.tmdb_region), rows, if (focus != 0) focus else at) { renderSettings(R.string.tmdb_region) }
        }
    }
}

/** The streaming services TMDB lists in your country, live, with their logos. OK switches the ones you pay for on or off; only those show on a title. */
internal fun MainActivity.pickTmdbServices(all: Boolean = false) {
    val key = Tmdb.key(this) ?: return toast(getString(R.string.tmdb_needs_key), long = true)
    val region = Tmdb.region(prefs)
    Work.net.execute {
        val list = try { Tmdb.providers(key, region) } catch (e: Exception) {
            android.util.Log.w("ATV", "tmdb providers failed: $e")
            ui.post { toast(getString(R.string.tmdb_unavailable), long = true) }
            return@execute
        }
        ui.post {
            if (isDestroyed) return@post
            val chosen = settings.tmdbServiceIds.toMutableSet()
            // The main services, then the five biggest of the country, then the ones already picked. "More services" shows the rest.
            val shown = if (all) list else list.filter { it.id in Tmdb.CORE } +
                list.filter { it.id !in Tmdb.CORE }.let { rest -> rest.take(5) + rest.drop(5).filter { it.id.toString() in chosen } }
            // The panel's list is 372 dp wide: four logos of 88 dp across fit; more would run off its edge.
            val cols = 4
            val grid = android.widget.GridLayout(this).apply { columnCount = cols }
            var first: View? = null
            for (p in shown) {
                val icon = ImageView(this).apply {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
                    }
                    clipToOutline = true
                    contentDescription = p.name
                    setBackgroundColor(ink(.08f))
                }
                val name = TextView(this).apply {
                    textSize = 10f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(valueInk())
                }
                val tile = LinearLayout(this).apply {
                    id = View.generateViewId()
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isFocusable = true
                    setPadding(dp(6), dp(6), dp(6), dp(4))
                    addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
                    addView(name, LinearLayout.LayoutParams(dp(76), WRAP_CONTENT))
                }
                fun paint() {
                    val on = p.id.toString() in chosen
                    icon.alpha = if (on) 1f else 0.4f
                    name.text = (if (on) "✓ " else "") + p.name
                }
                paint()
                tile.setOnClickListener {
                    if (!chosen.add(p.id.toString())) chosen.remove(p.id.toString())
                    settings.tmdbServiceIds = chosen.toSet()
                    settingsTmdbChanged()
                    paint()
                }
                panelFocus(tile, 12, null, AREA_ALL) {}
                posters.get(p.logo)?.let { icon.setImageBitmap(it) } ?: posterIo.execute {
                    val bmp = try { decode(p.logo, dp(52), Bitmap.Config.ARGB_8888) } catch (e: Exception) { null } ?: return@execute
                    posters.put(p.logo, bmp)
                    ui.post { icon.setImageBitmap(bmp) }
                }
                grid.addView(tile)
                if (first == null) first = tile
            }
            // The panel takes Left and Right for itself and only passes them on as a step (the view's tag), so the logos
            // step focus along the grid, and Up and Down go a row at a time.
            val tiles = (0 until grid.childCount).map { grid.getChildAt(it) }
            tiles.forEachIndexed { i, t ->
                t.tag = { d: Int -> tiles.getOrNull(i + d)?.requestFocus(); Unit }
                if (i - cols >= 0) t.nextFocusUpId = tiles[i - cols].id
                if (i + cols < tiles.size) t.nextFocusDownId = tiles[i + cols].id
                else if (i / cols < (tiles.size - 1) / cols) t.nextFocusDownId = tiles.last().id
            }
            showPage(getString(R.string.tmdb_services), listOf(
                page(getString(R.string.done), "") { renderSettings(R.string.tmdb_services) },
                page(getString(if (all) R.string.tmdb_services_main else R.string.tmdb_services_more), "") { pickTmdbServices(!all) },
            ),
                top = grid, crumb = region) { renderSettings(R.string.tmdb_services) }
            first?.requestFocus()
        }
    }
}

internal fun MainActivity.configureTmdb(receivedKey: String = "", back: () -> Unit) {
    val configured = Tmdb.key(this) != null
    lateinit var field: EditText
    fun save(key: String?) {
        if (!field.isEnabled) return
        if (key != null && !Tmdb.validKey(key)) {
            field.error = getString(R.string.tmdb_key_invalid)
            return
        }
        field.isEnabled = false
        Work.net.execute {
            val error = try {
                if (key != null && !Tmdb.acceptsKey(key)) R.string.tmdb_key_invalid
                else {
                    Tmdb.saveKey(this, key)
                    null
                }
            } catch (_: Exception) {
                R.string.tmdb_key_error
            }
            ui.post {
                if (isDestroyed) return@post
                if (error == null) {
                    heroList = emptyList() // a new key (or none) refetches the TMDB row
                    refreshHero()
                    if (field.isAttachedToWindow) {
                        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
                        back()
                    }
                } else if (field.isAttachedToWindow) {
                    field.isEnabled = true
                    field.requestFocus()
                    field.error = getString(error)
                }
            }
        }
    }
    field = textField(receivedKey, getString(R.string.tmdb_key), InputType.TYPE_TEXT_VARIATION_PASSWORD,
        EditorInfo.IME_ACTION_DONE) { save(it) }
    field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    field.isSaveEnabled = false
    val top = (field.parent as LinearLayout).apply {
        addView(label(getString(R.string.tmdb_help), 14f).apply {
            maxLines = 4
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }, 0)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.tmdb_logo)
            contentDescription = getString(R.string.tmdb_name)
        }, LinearLayout.LayoutParams(dp(52), dp(38)).apply { marginStart = dp(14); topMargin = dp(10) })
        addView(label(getString(R.string.tmdb_credit), 13f).apply {
            maxLines = 4
            setPadding(dp(14), dp(6), dp(14), dp(8))
        })
    }
    val rows = listOfNotNull(
        page(getString(R.string.done)) { save(field.text.toString().trim()) },
        page(getString(R.string.tmdb_from_phone)) {
            if (field.isEnabled) openTmdbKeyQr(field, back)
        },
        if (configured) page(getString(R.string.tmdb_turn_off)) { save(null) } else null,
    )
    showPage(getString(R.string.tmdb_artwork), rows, top = top, crumb = getString(R.string.wallpaper), back = back)
    settingsList.getChildAt(1).nextFocusUpId = field.id
    field.nextFocusDownId = settingsList.getChildAt(1).id
    if (receivedKey.isEmpty()) field.requestFocus()
}

internal fun MainActivity.openTmdbKeyQr(field: EditText, back: () -> Unit) {
    val ip = lanAddress() ?: return toast(getString(R.string.connect_network))
    val address = java.net.InetAddress.getByName(ip)
    if (!address.isSiteLocalAddress) return toast(getString(R.string.connect_network))
    val active = AtomicBoolean(true)
    val transfer = try {
        TmdbKeyTransfer(address, phoneText()) { key ->
            ui.post { if (!isDestroyed && active.get()) configureTmdb(key, back) }
        }
    } catch (_: IOException) {
        return toast(getString(R.string.tmdb_phone_unavailable))
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(context).apply {
            setImageBitmap(qrBitmap(transfer.url, dp(190), rounded = true))
            contentDescription = getString(R.string.tmdb_from_phone)
        }, LinearLayout.LayoutParams(dp(190), dp(190)))
        addView(label(getString(R.string.tmdb_scan), 14f).apply {
            maxLines = 4
            setPadding(dp(14), dp(10), dp(14), dp(6))
        })
    }
    val cancel = { configureTmdb(field.text.toString(), back) }
    showPage(getString(R.string.tmdb_from_phone), listOf(page(getString(R.string.cancel), action = cancel)),
        top = top, crumb = getString(R.string.tmdb_artwork), back = cancel)
    pageExit = {
        active.set(false)
        transfer.stop()
    }
}
