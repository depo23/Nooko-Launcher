package dev.nooko.launcher

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

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. Wallpaper: dark on a dark picture. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString("theme", "light")) {
    "dark" -> true
    "wallpaper" -> prefs.getInt("glass_hue", GLASS_GREY).let { (it shr 16 and 0xFF) * 299 + (it shr 8 and 0xFF) * 587 + (it and 0xFF) * 114 } < 128_000
    "auto" -> autoDark(sunPlace(prefs), System.currentTimeMillis(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    else -> false
}

/** The weather's location (found or chosen), kept on the TV: Auto follows the sun there. Null until there is one. */
internal fun sunPlace(prefs: SharedPreferences) =
    if (prefs.contains("geo_lat")) prefs.getFloat("geo_lat", 0f).toDouble() to prefs.getFloat("geo_lon", 0f).toDouble() else null

/** Dark from sunset to sunrise at [place]; with no place, from 19:00 to 7:00 as before. */
internal fun autoDark(place: Pair<Double, Double>?, now: Long, hour: Int) =
    if (place == null) hour !in 7..18 else Sun.dark(place.first, place.second, now)

/** "1.10" > "1.9" > "1.2.1" > "1.2": compared number by number, missing parts count as 0. */
internal fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** "external" covers every attached volume (USB drives too) from Android 10; before that only the main one. */
internal val PHOTOS: Uri by lazy { // lazy: unit tests load this file without Android
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/** Photo ids in a "folder:<bucket id>:<name>" choice. */
internal fun folderPhotos(cr: ContentResolver, choice: String): List<Long> =
    cr.query(PHOTOS, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(choice.split(':')[1]), null)
        ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } } ?: emptyList()

/** Picks the current or next photo from [ids], preserving [deck] if the media scan is still pending. */
internal fun pickFolderPhoto(
    ids: List<Long>,
    currentSaved: Long?,
    deck: ArrayDeque<Long>,
    previous: Long? = null,
    shuffle: (List<Long>) -> List<Long> = { it.shuffled() }
): Long? {
    if (ids.isEmpty()) return null
    val all = ids.toHashSet()
    if (currentSaved != null && currentSaved in all) return currentSaved
    deck.removeAll { it !in all }
    if (deck.isEmpty()) {
        deck.addAll(shuffle(ids))
        if (deck.size > 1 && deck.first() == previous) deck.addLast(deck.removeFirst())
    }
    return deck.removeFirstOrNull()
}

/** Whether the Google TV launcher package is active (not debloated or disabled). */
internal fun isGoogleTvLauncher(enabled: Boolean, setting: Int): Boolean =
    enabled && (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

/**
 * Sort key of a Watch Next row: what you started comes before suggestions (types other than 0, "continue"), then
 * newest first. Some apps write the time in seconds, not milliseconds (Wholphin does, for what is playing): a number
 * that small is read as seconds, or it would sort a thousand times older than every other row.
 */
internal fun watchSortKey(type: Long, engaged: Long): Long {
    val millis = if (engaged in 1 until 100_000_000_000L) engaged * 1000 else engaged
    return (if (type == 0L) 1L shl 50 else 0L) + millis
}

/**
 * Sort key of a card from an app's home-screen channel. Channels have no Watch Next type, so every card counts as
 * "continue". The card's own last-watched time when the app sets one (Nuvio does), else the time the app was last
 * opened from Nooko Launcher.
 */
internal fun channelSortKey(engaged: Long, opened: Long) = watchSortKey(0, if (engaged > 0) engaged else opened)

/**
 * Rows as (weight, sort key, item) to (sort key, item), highest weight first. A channel's cards often share one sort
 * key; the weight is the app's own order for them (Nuvio gives its newest the highest), and [newestPerApp] keeps it.
 */
internal fun <T> byWeight(rows: List<Triple<Long, Long, T>>) = rows.sortedByDescending { it.first }.map { it.second to it.third }

/**
 * A season or episode number fit to show, else null. Some apps store their own data in these columns (Alder puts "e"
 * and a long base64 string there), which would print as "Se EeyJzIjoi..." under the card.
 */
internal fun displayNumber(s: String?) = s?.trim()?.takeIf { it.length in 1..6 && it.any(Char::isDigit) }

/**
 * Newest first across apps (ties keep their order), each app's [perApp] newest ahead of the rest: one app's ten
 * shows can't bury the others. The rest follow, newest first, so an app on its own isn't held to [perApp].
 */
internal fun <T> newestPerApp(items: List<Pair<Long, T>>, perApp: Int, app: (T) -> String?): List<T> {
    val shown = HashMap<String?, Int>()
    val (front, rest) = items.sortedByDescending { it.first }.map { it.second }.partition { shown.merge(app(it), 1, Int::plus)!! <= perApp }
    return front + rest
}

/**
 * The cards of one source of a split row (By title, Per channel), in the order of the mixed row: newest first by the sort key, not
 * the order the channel lists them in. Turned-off apps and repeats are left out, and no more than [limit] stay.
 */
internal fun <T> sourceCards(items: List<Pair<Long, T>>, perApp: Int, limit: Int, app: (T) -> String?, off: (T) -> Boolean, same: (T) -> String): List<T> =
    newestPerApp(items, perApp, app).filter { !off(it) }.distinctBy(same).take(limit)

/**
 * Whether an app's home-screen channel feeds Continue Watching. Channels named "Continue Watching" (Stremio's) start
 * on, every other one (Plex On Deck, a Stremio catalog) starts off; [flipped] holds the ones the user switched.
 */
internal fun channelOn(name: String, key: String, flipped: Set<String>) =
    name.equals("Continue Watching", ignoreCase = true) != (key in flipped)

/** Layout lock PIN, stored as "salt:sha256". Keeps it out of plain sight in a prefs backup; not real security. */
internal fun hashPin(pin: String, salt: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) =
    salt + ":" + MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

internal fun pinMatches(stored: String, pin: String) = hashPin(pin, stored.substringBefore(':')) == stored

private val SET_TOP_WORDS = listOf("sky", "tivo", "xfinity", "comcast", "directv", "dish", "stb", "set-top", "settop", "cable", "satellite", "freesat", "freeview")
private val AUDIO_WORDS = listOf("soundbar", "sound bar", "sonos", "avr", "receiver", "home theater", "home theatre", "bose", "denon", "marantz", "onkyo", "yamaha", "harman", "jbl")

/**
 * Icon key for an HDMI tile, from its name: the CEC device name, or the name the user gave the input in TV
 * settings. Android doesn't give apps the CEC device type, so the name is all there is. One brand word covers
 * the family ("PlayStation" is PS3 to PS5). [named]: a CEC device, which is a player when nothing else matches.
 */
internal fun hdmiIcon(name: String, named: Boolean): String {
    val n = name.lowercase()
    return when {
        "playstation" in n -> "playstation"
        "xbox" in n -> "xbox"
        "nintendo" in n || "switch" in n -> "nintendoswitch"
        "apple tv" in n || "appletv" in n -> "appletv"
        "shield" in n -> "nvidia"
        "chromecast" in n || "google tv" in n -> "chromecast"
        SET_TOP_WORDS.any { it in n } -> "settop"
        named -> "playback"
        else -> "hdmi"
    }
}

/** Soundbars and AV receivers name themselves over CEC too, but there's nothing to switch to. */
internal fun isAudioDevice(name: String) = name.lowercase().let { n -> AUDIO_WORDS.any { it in n } }

/** An app is in the home row unless it was taken out; it stays in All apps either way. */
internal fun inRow(pkg: String, hidden: Set<String>) = pkg !in hidden

/**
 * Before the Sources list, an input with a signal or a CEC name was in the row without a pin. Once, on the update,
 * pin every input that was in the row so that no tile goes away. [inputs]: the input key and whether it has no signal.
 */
internal fun pinsAfterSourcesUpdate(inputs: List<Pair<String, Boolean>>, hidden: Set<String>, pinned: Set<String>) =
    pinned + inputs.filter { (pkg, offline) -> pkg !in hidden && (!offline || pkg in pinned) }.map { it.first }

/** The icon styles, in the order the setting steps through them. */
internal val ICON_STYLE_KEYS = listOf("mono", "colour", "banner", "bannermono")

/** Banner and Monochrome banner: the row shows the apps' own pictures in 16:9 tiles. */
internal fun isBannerStyle(style: String) = style.startsWith("banner")

/** The style a stored choice means. Before styles, Colour was an on/off setting ([oldColour]): it decides until a style is picked. */
internal fun iconStyleOf(stored: String?, oldColour: Boolean) =
    stored?.takeIf { it in ICON_STYLE_KEYS } ?: if (oldColour) "colour" else "mono"

/** Allowed to start an activity from the background because the Home button service is bound. */
class Updated : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        File(c.cacheDir, ApkProvider.NAME).delete() // the downloaded update (see installUpdate)
        c.startActivity(Intent(c, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        dropSessions(c)
    }
}

/** Sessions the TV's own installer left waiting for the confirm box (see MainActivity.openApk). */
internal fun dropSessions(c: Context) {
    val installer = c.packageManager.packageInstaller
    installer.mySessions.forEach {
        try {
            installer.abandonSession(it.sessionId)
        } catch (_: Exception) { // already gone
        }
    }
}

internal fun <T> preferTvApps(tv: List<T>, other: List<T>, pkg: (T) -> String) = (tv + other).distinctBy(pkg)

class MainActivity : Activity() {

    /**
     * An app's icon is loaded once, but not held: the mono mark and the accent colour are made from it and kept, and a
     * tile that shows the icon holds its own copy, so ~20 launcher icons (up to a megabyte each) need not stay in memory.
     * If the garbage collector took it before it was needed, [load] brings it back.
     * [offline]: an HDMI port with no signal right now (the Sources list says so).
     */
    internal class App(val pkg: String, val label: String, val launch: Intent, icon: Drawable, val offline: Boolean = false, private val load: (() -> Drawable)? = null) {
        private val strong = if (load == null) icon else null // our own drawables (HDMI marks) are tiny and have no loader
        private var weak = java.lang.ref.WeakReference(icon)
        val icon: Drawable get() = strong ?: weak.get() ?: load!!().also { weak = java.lang.ref.WeakReference(it) }
    }
    /** [group]: what shares the front places in Continue Watching, the app or, for an app channel, that channel. */
    internal class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?,
                       val group: String? = pkg, val wallpaperArt: List<String> = emptyList(), val tmdbType: String? = null)
    /** One row of cards you can switch to: Watch Next, or one app channel that is on. */
    internal class CardSource(val key: String, val title: String)
    /** What the shows row holds: the [sources] it is split in (none when mixed), the one on show and its cards. */
    internal class Cards(val sources: List<CardSource>, val current: CardSource?, val list: List<Prog>, val ahead: List<String> = emptyList())

    internal val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    internal val settings by lazy { Settings(prefs) }
    internal val io = Executors.newSingleThreadExecutor()
    /** Small wallpaper pictures for the settings grids, by choice and size; 8 MB holds every built-in twice over. */
    private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Posters get their own threads: one slow image server must not hold up the rest (or the row data).
    internal val posterIo = Executors.newFixedThreadPool(3)
    private val loading = java.util.Collections.synchronizedSet(HashSet<String>())
    internal val ui = Handler(Looper.getMainLooper())
    /** Recent apps' full-size Continue Watching art, apart so it never pushes the row's posters out. */
    private val windowArt = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // 16 MB: the 30 TMDB cards are about 0.7 MB each, so a row of them stays in memory while you scroll it.
    internal val posters = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Kept across activity rebuilds (theme switch), so the new home screen shows its apps at once.
    internal var apps = APPS ?: emptyList()
    internal var sources = SOURCES ?: emptyList() // HDMI ports and the devices on them: the Sources list, not the apps
    internal var cvteSourcePanel = CVTE_SOURCE_PANEL
    internal var appsDirty = APPS == null
    internal var watchKey: String? = null
    internal var cardSources = emptyList<CardSource>() // By title and Per channel: the sources the shows row is split in
    internal var cardCurrent: CardSource? = null
    internal var switching = false // the next list is another source of the same row: its cards are swapped in place
    internal var lastTop: View? = null // last focused view in each home row, so Up/Down return to it
    internal var lastApp: View? = null
    private var lastCard: View? = null
    internal var editing = false
    private var grabbed: View? = null
    private var holdFired = false
    internal var dark = false
    internal var fg = Color.BLACK
    internal var ground = Color.WHITE
    private var cardRest = intArrayOf(Color.WHITE, Color.WHITE)
    private var cardLit = cardRest

    internal lateinit var appRow: LinearLayout
    private lateinit var watchRow: LinearLayout
    private lateinit var watchTitle: CrossfadeLabel
    internal val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    private lateinit var watchScroll: View
    internal lateinit var heroScroll: View
    internal lateinit var heroRow: LinearLayout
    internal lateinit var moreApps: LinearLayout
    internal lateinit var heroTitle: TextView
    internal lateinit var billboard: View
    internal lateinit var bbInfo: View
    internal lateinit var bbTitle: TextView
    internal lateinit var bbMeta: TextView
    internal lateinit var bbSynopsis: TextView
    internal lateinit var bbCast: TextView
    internal lateinit var bbButton: TextView
    internal lateinit var bbDots: TextView
    internal lateinit var bbProviders: LinearLayout
    internal lateinit var bbGenres: TextView
    internal lateinit var heroBackdrop: View
    internal lateinit var heroBgA: ImageView
    internal lateinit var heroBgB: ImageView
    internal lateinit var moreShade: View
    internal var bbIndex = 0
    internal var bbFront = false // true while heroBgB is the one showing
    internal var bbSkips = 0 // titles skipped in a row because they did not load completely
    internal val heroBg = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    internal var lastMore: View? = null
    internal var heroList = emptyList<Tmdb.Hero>()
    internal var heroAt = 0L
    internal var lastHero: View? = null
    internal val heroDetails = HashMap<String, Tmdb.Details>()
    internal val heroTick = object : Runnable {
        override fun run() {
            heroAdvance()
            ui.postDelayed(this, HERO_SCROLL_MS)
        }
    }
    internal val appScroll by lazy { findViewById<View>(R.id.appScroll) }
    private var titleAlpha = 1f // the row title's own alpha from the layout, read before placeRow fades it
    private var rowOut = false // "Show row: On Down": the bottom row has focus, so it's slid in
    internal lateinit var allApps: View
    internal lateinit var allAppsGrid: GridLayout
    internal lateinit var wifi: ImageView
    internal lateinit var glass: GlassLayer // paints the blurred wallpaper under glass tiles
    internal fun glassReady() = ::glass.isInitialized // false until the screen is built

    internal var resumed = false
    internal var ready = false // false while the user is still locked: no prefs, files or cache yet
    private var recreating = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = rebuildWhenUnlocked()
    }
    private val refreshIfDirty = Runnable { if (appsDirty && !editing) refreshApps() } // not while a tile is being moved: onResume catches up

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
            i.data?.schemeSpecificPart?.let { GLYPHS.remove(it); ACCENTS.remove(it) } // an update may bring a new icon
            // The system can tell us after we are back in front, for example after an uninstall: onResume has run already.
            if (resumed) {
                ui.removeCallbacks(refreshIfDirty)
                ui.postDelayed(refreshIfDirty, 300L)
            }
        }
    }

    internal val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            ui.post {
                if (isDestroyed) return@post
                showNet(caps)
                // Online again (e.g. Wi-Fi coming up after boot): fetch any posters that failed meanwhile.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryPosters()
            }
        }

        override fun onLost(n: Network) {
            ui.post { showNet(null) }
        }
    }

    /** The user is unlocked now: rebuild the home screen, so it reads the now-available saved data. */
    private fun rebuildWhenUnlocked() {
        if (ready || recreating || isFinishing || isDestroyed) return
        recreating = true
        recreate()
    }

    // Every launch from the home screen goes through here, so Fire TV's Home watch knows Amazon's home may show first.
    override fun startActivity(intent: Intent?, options: Bundle?) {
        HomeOverride.running?.launched()
        super.startActivity(intent, options)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // The system starts Home before the user unlocks (Direct Boot). Register first, so a missed
        // broadcast cannot leave the home screen empty. Do not read prefs, files or the cache yet.
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        if (!getSystemService(UserManager::class.java).isUserUnlocked) {
            setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
            return
        }
        ready = true
        setContentView(R.layout.activity_main)
        glass = GlassLayer(this)
        findViewById<ViewGroup>(R.id.root).addView(glass, 3, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        for (id in intArrayOf(R.id.appScroll, R.id.watchScroll, R.id.allApps)) findViewById<View>(id).setOnScrollChangeListener { _, _, _, _, _ -> glass.kick(150) }
        appRow = findViewById(R.id.appRow)
        watchRow = findViewById(R.id.watchRow)
        watchTitle = findViewById(R.id.watchTitle)
        titleAlpha = watchTitle.alpha
        watchScroll = findViewById(R.id.watchScroll)
        heroScroll = findViewById(R.id.heroScroll)
        heroRow = findViewById(R.id.heroRow)
        moreApps = findViewById(R.id.moreApps)
        heroTitle = findViewById(R.id.heroTitle)
        billboard = findViewById(R.id.billboard)
        bbInfo = findViewById(R.id.bbInfo)
        bbTitle = findViewById(R.id.bbTitle)
        bbMeta = findViewById(R.id.bbMeta)
        bbSynopsis = findViewById(R.id.bbSynopsis)
        bbCast = findViewById(R.id.bbCast)
        bbButton = findViewById(R.id.bbButton)
        bbDots = findViewById(R.id.bbDots)
        bbProviders = findViewById(R.id.bbProviders)
        bbGenres = findViewById(R.id.bbGenres)
        heroBackdrop = findViewById(R.id.heroBackdrop)
        heroBgA = findViewById(R.id.heroBgA)
        heroBgB = findViewById(R.id.heroBgB)
        moreShade = findViewById(R.id.moreShade)
        setUpBillboard()
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, v -> ensureHomeVisible(v) }
        allApps = findViewById(R.id.allApps)
        allAppsGrid = findViewById(R.id.allAppsGrid)
        wifi = findViewById(R.id.wifi)
        // A new install starts with the light theme and Frosted glass.
        val info = packageManager.getPackageInfo(packageName, 0)
        if (info.firstInstallTime == info.lastUpdateTime && !prefs.contains("wp_light") && !prefs.contains("wp_dark"))
            prefs.edit().putString("theme", "light").putString("glass_style", GlassStyle.FROSTED.key).apply()
        File(filesDir, "paintings").deleteRecursively() // the screensaver paintings are gone: give the space back
        File(filesDir, "wallpaper-pack").deleteRecursively() // and the wallpaper pack
        if (!prefs.getBoolean("wp_shared", false)) {
            val wasDark = wantDark()
            val choice = prefs.getString(if (wasDark) "wp_dark" else "wp_light", null)
                ?: Bing.CHOICE
            val photo = prefs.getLong(if (wasDark) "wp_photo_dark" else "wp_photo_light", -1)
            val at = prefs.getLong(if (wasDark) "wp_photo_at_dark" else "wp_photo_at_light", System.currentTimeMillis())
            prefs.edit().putString("wp_light", choice).putString("wp_dark", choice)
                .putLong("wp_photo_light", photo).putLong("wp_photo_at_light", at)
                .putBoolean("wp_shared", true).apply()
        }
        // The Reddit wallpaper is now Wallhaven: Reddit stopped answering apps.
        for (k in listOf("wp_light", "wp_dark")) if (prefs.getString(k, null) == "reddit") prefs.edit().putString(k, Wallhaven.CHOICE).apply()
        // The Cue timer icon in a saved top bar order is gone: the heart takes its place.
        prefs.getString("top_bar", null)?.takeIf { "timer" in it.split(',') }?.let { prefs.edit().putString("top_bar", it.split(',').joinToString(",") { k -> if (k == "timer") "heart" else k }).apply() }
        // One-time move to the Apple TV look: light theme, frosted glass, banner tiles five across on a shelf, a soft gradient.
        if (!prefs.getBoolean("look_v2", false)) {
            prefs.edit().putString("theme", "light").putString("glass_style", GlassStyle.FROSTED.key)
                .putString("icon_style", "banner").putString("row_size", "tv")
                .putString("wp_light", Bing.CHOICE).putString("wp_dark", Bing.CHOICE).putBoolean("look_v2", true).apply()
        }
        applyTopBar()
        applyTheme()
        setUpTitle() // after the theme: the pill is styled with the theme's card colours
        glassShown = glassLook() to null // the tiles are built for this look; only the blur is still to come
        loadWallpaper()
        // "Show row: On Down": the row slides in while one of its cards has focus, however focus got there.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val out = ShowRows.slidesIn(rowShow(), onCard = now?.getTag(R.id.watchRow) is Prog, onTitle = now === watchTitle)
            if (out != rowOut) { rowOut = out; placeRow(animate = true) }
        }
        watchScroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) placeRow(animate = false) // the drop is measured from the layout
        }

        // Top bar: network, All apps, Android settings, launcher settings. Round, lit on focus.
        for ((id, action) in listOf<Pair<Int, () -> Unit>>(
            R.id.wifi to { unlock { openSettings(Settings.ACTION_WIFI_SETTINGS) } },
            R.id.sourcesButton to { showSources(findViewById(R.id.sourcesButton)) },
            R.id.allAppsButton to { openAllApps() },
            R.id.systemSettings to { unlock { openSettings(Settings.ACTION_SETTINGS) } },
            R.id.launcherSettings to { unlock { openLauncherSettings() } },
            R.id.powerButton to { powerDialog() },
            R.id.heartButton to { heartPopup() },
            R.id.recentsButton to { openAllApps(recent = true) },
            R.id.screensaverButton to { startScreensaver() },
        )) findViewById<ImageView>(id).apply {
            setColorFilter(fg)
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            // Lit, the icon sits on its ring, so it takes the theme colour; otherwise it's on the wallpaper (see fitInk).
            // Under the ring, the icon's shadow (see iconShadow); empty until fitInk.
            background = LayerDrawable(arrayOf(ColorDrawable(Color.TRANSPARENT), ring))
            setOnFocusChangeListener { v, focused ->
                if (focused) lastTop = v
                (background as LayerDrawable).getDrawable(0).alpha = if (focused) 0 else 255 // on its disc it needs none
                // With glass a lit icon sits on a glass disc: the frost, its light and the wallpaper's tint.
                val disc = glassActive()
                ring.setColor(if (!focused) 0 else if (disc) glassLook().tile(lit = true) else if (dark) DARK_CARD_LIT[0] else Color.WHITE)
                if (focused && disc) glass.add(v, v.width / 2f) else glass.remove(v)
                if (disc) glass.kick(260) // the disc grows with the icon
                setColorFilter(if (focused) fg else iconInk)
                animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                    .setDuration(160).setInterpolator(DECELERATE).start()
            }
            setOnClickListener { action() }
            setOnLongClickListener { if (!holdFired) fireHold(this); true } // hold OK: edit the bar
        }
        if (intent.getBooleanExtra(Remap.OPEN_ALL_APPS, false) || intent.getBooleanExtra(Remap.OPEN_RECENT_APPS, false) || fakeSetup(intent)) {
            // An explicit drawer request takes priority over setup and update cards.
        } else if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().remove("reopen_settings").apply()
            ui.post { openLauncherSettings(R.string.theme) }
        } else if (!homeSetUp() && !prefs.getBoolean("setup_shown", false)) {
            // Fresh install: walk through making Home land on Nooko Launcher, the one step that has to happen by hand.
            prefs.edit().putBoolean("setup_shown", true).apply()
            ui.post { startHomeSetup() }
        } else if (notesUnseen()) {
            ui.post { whatsNewCard() } // after an update; the self-updater reopens Nooko Launcher, so it's seen right away
        }

        try {
            contentResolver.registerContentObserver(PHOTOS, true, photoObserver)
        } catch (e: Exception) {
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        // Application context: ConnectivityManager keeps its first instance (and that instance's context) in a
        // static callback handler forever, so an Activity context here leaks the whole first home screen.
        applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, ui)

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) {
            if (getSystemService(UserManager::class.java).isUserUnlocked) rebuildWhenUnlocked()
            return
        }
        resumed = true
        val recentAppsRequested = intent.getBooleanExtra(Remap.OPEN_RECENT_APPS, false)
        val allAppsRequested = intent.getBooleanExtra(Remap.OPEN_ALL_APPS, false) || recentAppsRequested
        returnTo = null // back by another way than Home: the next Home starts again at the first tile
        openedFrom?.get()?.let { tile -> ui.post { if (tile.isAttachedToWindow) settleRow(tile) } }
        openedFrom = null
        ensureHomeService()
        if (!recentAppsRequested) homeSetupResumed()
        // Back from a screen that settings opened: settings again, on the same row (and the Home switch as it is now).
        val back = if (allAppsRequested) 0 else prefs.getInt("return_settings", 0)
        if (back != 0 && hidden) {
            prefs.edit().remove("return_settings").remove("return_dream").apply()
            if (settingsScrim.visibility == View.VISIBLE) renderSettings(back) else openLauncherSettings(back)
        }
        hidden = false
        if (wantDark() != dark) {
            recreate()
            return
        }
        // Only rebuild what changed, so focus stays where it was when returning from an app.
        if (appsDirty) refreshApps() else if (appRow.childCount == 0) renderApps()
        refreshWatchNext()
        refreshHero()
        showWeather()
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)
        ui.postDelayed(themeTick, 60_000L)
        ui.removeCallbacks(wallpaperTick)
        ui.post(wallpaperTick)
        // Gallery chosen but its paintings missing (updated from a version that bundled them): fetch them.
        // The paintings screensaver is gone: a TV that still had it moves to the TMDB screensaver, or to the system's own.
        if (galleryOn() && prefs.getString("ss_folder", null) == null) {
            if (Tmdb.key(this) != null) prefs.edit().putString("ss_folder", TMDB_SCREENSAVER).apply() else stopGallery()
        }
        checkUpdate(force = false)
        Bing.onChanged = bingChanged
        Bing.refresh(applicationContext, prefs)
        Wallhaven.onChanged = wallhavenChanged
        Wallhaven.refresh(applicationContext, prefs)
        ui.post { updateBadge() } // after layout, so the icon has its size
        if (allAppsRequested) {
            intent.removeExtra(Remap.OPEN_ALL_APPS) // consume only after initialization and any theme recreation
            intent.removeExtra(Remap.OPEN_RECENT_APPS)
            prefs.edit().remove("return_settings").remove("return_dream").remove("reopen_settings").apply()
            if (editing) endEdit()
            endTopEdit()
            if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
            if (recentAppsRequested || allApps.visibility != View.VISIBLE || recentAppsOpen) openAllApps(recentAppsRequested)
        }
    }

    /** Auto theme flips at sunrise and sunset (or 7:00 and 19:00) even while the home screen just sits there. */
    private val themeTick = object : Runnable {
        override fun run() {
            if (wantDark() != dark && !wallpaperPickerOpen()) recreate() else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != MEDIA_REQUEST) return refreshWatchNext()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPhotoAccess?.invoke()
        else toast(getString(R.string.photos_permission), long = true)
    }

    @Deprecated("Only used for the default-home role request and the TV's own installer")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == INSTALL_REQUEST) installerClosed()
        if (code == ROLE_REQUEST && settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.default_launcher)
        if (code == ROLE_REQUEST && setupStep == 1) {
            setupStep = 0
            afterHomeRole()
        }
    }

    override fun onPause() {
        if (ready) {
            resumed = false
            findViewById<ImageView>(R.id.wallpaper).alpha = 1f
            ui.removeCallbacks(refreshIfDirty)
            ui.removeCallbacks(weatherTick)
            ui.removeCallbacks(themeTick)
            ui.removeCallbacks(wallpaperTick)
            if (editing) endEdit()
            endTopEdit() // Home or an app opened in the middle of an edit: a lifted icon goes back
        }
        super.onPause()
    }

    override fun onStop() {
        if (ready) {
            if (upload != null) closeLauncherSettings()
            hidden = true
            unlockedUntil = 0L // leaving the home screen locks it again (onPause would also fire for permission dialogs)
            Remap.learn = null // never keep swallowing keys for a page that's no longer on screen
            if (homeTest != 0) leftDuringTest = true
        }
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
    }

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        if (ready) {
            if (Bing.onChanged === bingChanged) Bing.onChanged = null
            if (Wallhaven.onChanged === wallhavenChanged) Wallhaven.onChanged = null
            unregisterReceiver(packageReceiver)
            applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net)
            getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback)
            pageExit?.invoke()
            pageExit = null
            upload?.stop()
            try {
                contentResolver.unregisterContentObserver(photoObserver)
            } catch (e: Exception) {
            }
            ui.removeCallbacks(folderRetryTask)
            ui.removeCallbacksAndMessages(null) // the clocks and the posts still queued must not use the pools below
            io.shutdownNow()
            posterIo.shutdownNow()
        }
        super.onDestroy()
    }

    /**
     * Android drops our Home service from the enabled accessibility services whenever the app is
     * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission),
     * unless the user turned it off.
     */
    private fun ensureHomeService() {
        if (homeServiceOn()) return prefs.edit().remove("home_off").apply() // what Android says wins
        HomeAlarm.release(this) // the service died without telling us: its alarm setting must not stay
        if (prefs.getBoolean("home_off", false) || checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(this, HomeOverride::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isEmpty()) me else "$on:$me")
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Compared as components: Settings may store us in short form (dev.nooko.launcher/.HomeOverride). */
    internal fun homeServiceOn(): Boolean {
        val me = ComponentName(this, HomeOverride::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    internal fun toggleHomeButton() = if (homeServiceOn()) turnOffHomeButton() else turnOnHomeButton()

    /** Off hands Home back to the TV's own launcher, and Nooko Launcher leaves it off until switched on again. */
    private fun turnOffHomeButton() {
        prefs.edit().putBoolean("home_off", true).apply()
        HomeOverride.running?.disableSelf()
        if (homeServiceOn() && checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
            val me = ComponentName(this, HomeOverride::class.java)
            val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on.split(':').filter { ComponentName.unflattenFromString(it) != me }.joinToString(":"))
        }
        // disableSelf takes a moment: only send the user to Accessibility if the service is still on after it.
        ui.postDelayed({
            if (homeServiceOn()) {
                toast(getString(R.string.home_off_hint), long = true, system = true)
                returnTo(R.string.home_button)
                openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            } else if (settingsScrim.visibility == View.VISIBLE && lastSettings == R.string.section_remote_home) renderSettings(R.string.home_button)
        }, 800)
        renderSettings(R.string.home_button)
    }

    /** The Home key service is an accessibility service, which Android only lets the user switch on. */
    private fun turnOnHomeButton() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) {
            // A switch-off just before (disableSelf) can land after this write: check again, and write again if it did.
            ui.postDelayed({ ensureHomeService(); if (settingsScrim.visibility == View.VISIBLE && lastSettings == R.string.section_remote_home) renderSettings(R.string.home_button) }, 800)
            return renderSettings(R.string.home_button)
        }
        // The written path is the safety net: some Google TVs (a Xiaomi stick) answer the Accessibility link
        // with "You don't have an app that can do this", which we can't detect from here.
        toast(getString(R.string.home_on_hint), long = true, system = true)
        returnTo(R.string.home_button)
        openHomeServiceSettings()
    }

    /** False when no Settings screen opened at all. */
    private fun openHomeServiceSettings(): Boolean {
        val service = ComponentName(this, HomeOverride::class.java).flattenToString()
        val tries = listOf(
            // Straight to our own toggle (Android 12+, where the TV supports it), then the list, then Settings.
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try {
                startActivity(intent) // not resolveActivity(): Android 11+ hides other apps from that
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    /** Whether Android resolves the Home intent to Nooko Launcher. Xiaomi can grant the Home role without routing Home to Nooko Launcher. */
    internal fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    /**
     * Asks Android to make us the default home app (its own confirmation dialog). On Google TV the system
     * launcher still wins Home intents, which is why the Home button service exists; on other Android TVs
     * this alone is enough.
     */
    internal fun makeDefaultHome() {
        returnTo(R.string.default_launcher)
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        } else {
            openSettings(Settings.ACTION_HOME_SETTINGS) // Android lets apps ask for Home, not give it back
        }
    }

    // ---------- Home setup ----------
    // Popups that try each way of making Home land on Nooko Launcher, stopping at the first that works: the default-home
    // role (skipped on Google TV and Fire TV, which ignore it), then the Home button service, then on Android 13+
    // App info's "Allow restricted settings" for sideloaded apps, then adb help for TVs that still grey it out.
    // Each way is checked by asking for a Home press: one that reaches us arrives as a Home intent; one that
    // doesn't sends us to the background.

    internal var setupDialog: Dialog? = null
    private var setupStep = 0 // 1 role dialog open, 2 Accessibility settings open, 3 App info open
    private var homeTest = 0 // the way being checked (1 role, 2 service) while waiting for a Home press
    private var leftDuringTest = false
    private var previewAt = -1 // debug builds: which popup the preview is on, -1 when not previewing
    private var restrictedTried = false // App info's "Allow restricted settings" already offered this round

    /** Google TV takes the home role but its own launcher still gets Home, so only the service works there. */
    internal fun googleTv() = fakeDevice?.let { it == "google" } ?: try {
        val app = packageManager.getApplicationInfo(HomeWatch.GOOGLE_TV, 0)
        val setting = packageManager.getApplicationEnabledSetting(HomeWatch.GOOGLE_TV)
        isGoogleTvLauncher(app.enabled, setting)
    } catch (e: Exception) {
        false
    }

    internal fun fireTv() = fakeDevice?.let { it == "fire" } ?: HomeOverride.fireTv(this)
    private fun sdk() = if (fakeSdk > 0) fakeSdk else Build.VERSION.SDK_INT

    /**
     * Debug builds: act as another TV and start setup as on a first launch, to walk each device's popups here.
     * adb shell am start -n dev.nooko.launcher/.MainActivity --es fake_device fire|google|stock|real --ei fake_sdk 33
     * Only the setup's choices change; the TV's own screens and Home key stay what they are.
     */
    private fun fakeSetup(intent: Intent?): Boolean {
        val device = intent?.getStringExtra("fake_device") ?: return false
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        fakeDevice = device.takeIf { it != "real" }
        fakeSdk = intent.getIntExtra("fake_sdk", 0)
        ui.post { startHomeSetup() }
        return true
    }

    /** Home opens Nooko Launcher one way or the other: the service anywhere, the role only where the TV honours it. */
    internal fun homeSetUp() = homeServiceOn() || (isDefaultHome() && !googleTv() && !fireTv())

    internal fun startHomeSetup() {
        restrictedTried = false
        // Fire OS takes the role request and ignores it; Google TV grants it and still opens its own launcher.
        if (fireTv() || googleTv()) return tryHomeService()
        val roles = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) rolePopup()
        else afterHomeRole()
    }

    private fun afterHomeRole() = if (isDefaultHome() || homeServiceOn()) testPopup(1) else tryHomeService()

    /** Service still off after Accessibility: on Android 13+ a sideloaded app's toggle is greyed until App info allows it. */
    private fun serviceStillOff() = if (sdk() >= 33 && !restrictedTried) restrictedPopup() else adbPopup()

    private fun tryHomeService() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) testPopup(2) else servicePopup()
    }

    /** Back from the role dialog, Accessibility settings, or another home screen after a Home press. */
    private fun homeSetupResumed() {
        if (setupStep == 2) {
            setupStep = 0
            if (homeServiceOn()) testPopup(2) else serviceStillOff()
        } else if (setupStep == 3) {
            setupStep = 0
            servicePopup()
        } else if (homeTest != 0 && leftDuringTest) {
            val failed = homeTest
            homeTest = 0
            if (failed == 1) tryHomeService() else adbPopup()
        }
    }

    private fun rolePopup() = homePopup(1, getString(R.string.setup_role_title), getString(R.string.setup_role_body), null,
        getString(R.string.setup_continue) to {
            setupStep = 1
            @Suppress("DEPRECATION")
            startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    private fun testPopup(way: Int) {
        if (previewAt < 0) {
            homeTest = way
            leftDuringTest = false
        }
        homePopup(way, getString(R.string.setup_test_title), getString(R.string.setup_test_body), null,
            getString(R.string.setup_skip_check) to { homeTest = 0; if (way == 1) tryHomeService() else adbPopup() })
    }

    private fun servicePopup() = homePopup(2, getString(R.string.setup_service_title), getString(R.string.setup_service_body), null,
        getString(R.string.setup_open_accessibility) to {
            setupStep = 2
            if (!openHomeServiceSettings()) {
                setupStep = 0
                adbPopup()
            }
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    /** Android 13+ "restricted settings": App info has "Allow restricted settings", as a row or under ⋮, on TVs that show it. */
    private fun restrictedPopup() {
        if (previewAt < 0) restrictedTried = true
        homePopup(2, getString(R.string.setup_restricted_title), getString(R.string.setup_restricted_body), null,
            getString(R.string.setup_open_app_info) to {
                setupStep = 3
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } catch (e: Exception) {
                    setupStep = 0
                    adbPopup()
                }
            },
            getString(R.string.setup_no_menu) to { adbPopup() })
    }

    /** A friendly ask for support: one of a few messages, and a large QR code for Ko-fi that a phone reads from the couch. */
    private var heartLast = -1

    internal fun heartPopup() {
        val messages = resources.getStringArray(R.array.heart_messages)
        // A different message each time the heart is selected, never the same one twice in a row.
        val pick = (messages.indices - heartLast).random().also { heartLast = it }
        val side = dp(230)
        val qr = ImageView(this).apply {
            setImageResource(R.drawable.kofi_qr)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.heart_scan), 16f).apply { maxLines = 3; ellipsize = null; setLineSpacing(0f, 1.15f) })
            addView(label(KOFI_URL.removePrefix("https://"), 20f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
        }
        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(qr, LinearLayout.LayoutParams(side, side))
            addView(text, LinearLayout.LayoutParams(0, side, 1f).apply { marginStart = dp(24) })
        }
        homePopup(0, getString(R.string.heart_label), messages[pick], extra,
            getString(R.string.setup_close) to { setupDialog?.dismiss() }, frosted = true)
    }

    /**
     * Asks the TV's own adb for the one permission only adb can give (see [AdbSelf]), then runs [then]. The person turns on ADB
     * debugging and answers one question on the TV; no computer.
     */
    internal fun askSecureGrant(then: () -> Unit) {
        if (checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) return then()
        homePopup(0, getString(R.string.adb_allow_title), getString(R.string.adb_allow_body), null,
            getString(R.string.adb_allow_now) to { setupDialog?.dismiss(); runSecureGrant(then) },
            getString(R.string.adb_show_command) to { adbPopup() },
            getString(R.string.setup_not_now) to { setupDialog?.dismiss() })
    }

    private fun runSecureGrant(then: () -> Unit) {
        toast(getString(R.string.adb_connecting), long = true, system = true)
        val hosts = listOfNotNull("127.0.0.1", lanAddress())
        Work.net.execute {
            val result = AdbSelf.grant(this, WRITE_SECURE, hosts) { ui.post { toast(getString(R.string.adb_approve), long = true, system = true) } }
            ui.post {
                if (isDestroyed) return@post
                when (result) {
                    AdbSelf.Result.DONE -> { toast(getString(R.string.adb_done), long = true, system = true); then() }
                    AdbSelf.Result.NOT_LISTENING -> homePopup(0, getString(R.string.adb_off_title), getString(R.string.adb_off_body), null,
                        getString(R.string.setup_try_again) to { setupDialog?.dismiss(); runSecureGrant(then) },
                        getString(R.string.adb_show_command) to { adbPopup() })
                    AdbSelf.Result.NOT_ALLOWED -> homePopup(0, getString(R.string.adb_denied_title), getString(R.string.adb_denied), null,
                        getString(R.string.setup_try_again) to { setupDialog?.dismiss(); runSecureGrant(then) },
                        getString(R.string.adb_show_command) to { adbPopup() })
                    AdbSelf.Result.FAILED -> homePopup(0, getString(R.string.adb_failed_title), getString(R.string.adb_failed), null,
                        getString(R.string.adb_show_command) to { adbPopup() },
                        getString(R.string.setup_close) to { setupDialog?.dismiss() })
                }
            }
        }
    }

    /** The one-time grant lets Nooko Launcher switch the service on itself, past the greyed-out toggle. */
    private fun adbPopup() {
        val mono = Typeface.MONOSPACE
        val code = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) DARK_BG else 0xFFE9EBEF.toInt())
            }
            listOfNotNull(lanAddress()?.let { "adb connect $it:5555" }, "adb shell pm grant $packageName $WRITE_SECURE").forEach {
                addView(label(it, 12f).apply { typeface = mono; maxLines = 2; ellipsize = null; setPadding(0, dp(2), 0, dp(2)) })
            }
        }
        val qr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageBitmap(qrBitmap(HELP_URL, dp(108))) }, LinearLayout.LayoutParams(dp(108), dp(108)))
            addView(label(getString(R.string.setup_scan_help), 11f).apply { alpha = 0.6f; setPadding(0, dp(6), 0, 0) })
        }
        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) })
        }
        homePopup(3, getString(R.string.setup_adb_title), getString(R.string.setup_adb_body), extra,
            getString(R.string.adb_allow_now) to { setupDialog?.dismiss(); runSecureGrant { tryHomeService() } }, // the grant also switches the Home service on
            getString(R.string.setup_try_again) to { tryHomeService() },
            getString(R.string.setup_close) to { setupDialog?.dismiss() })
    }

    /**
     * Once after an update: the first six CHANGELOG.md entries of this version, so put the best ones first.
     * The card shows each entry's bold headline and the sentence after it; See all changes shows the rest.
     * Closing it counts as reading the notes, which clears the dot on the Nooko Launcher settings icon.
     */
    private fun whatsNewCard() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val notes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            getString(R.string.whats_new).split('\n').filter { !it.startsWith("#") }.take(6).forEach {
                val head = it.indexOf("**", 2)
                val cut = if (head < 0) -1 else it.indexOf(". ", head + 2)
                addView(label("", 15f).apply {
                    text = noteText(if (cut < 0) it else it.substring(0, cut + 1))
                    maxLines = Int.MAX_VALUE
                    ellipsize = null
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, dp(12), 0, 0)
                })
            }
        }
        homePopup(0, getString(R.string.whats_new_card_title, myVersion()), null, notes,
            getString(R.string.done) to { setupDialog?.dismiss() },
            getString(R.string.see_all_changes) to {
                setupDialog?.dismiss()
                unlock { openLauncherSettings(R.string.updates); showUpdates() }
            }, frosted = true)
    }

    private fun donePopup(): Unit {
        prefs.edit().putBoolean("setup_shown", true).apply()
        homePopup(0, getString(R.string.setup_done_title),
            getString(if (homeServiceOn() && !fireTv()) R.string.setup_done_body_service else R.string.setup_done_body), null,
            getString(R.string.setup_done) to { setupDialog?.dismiss() })
    }

    /**
     * A card in the middle of the screen over the dimmed home screen, in the hold menus' colours.
     * [frosted] makes it Frosted glass whatever the Glass setting is, when there is a picture to blur.
     */
    internal fun homePopup(step: Int, title: String, body: String?, extra: View?, vararg buttons: Pair<String, () -> Unit>, frosted: Boolean = false) {
        setupDialog?.let { setupDialog = null; it.dismiss() } // replaced, not closed: the dismiss listener leaves the flow alone
        val frost = frosted && Backdrop.of(currentWallpaper()) != Backdrop.PLAIN
        val card: LinearLayout = if (frost || glassActive()) glassSurface(dp(20).toFloat()).also { box ->
            if (frost) {
                // Its own blur, not the setting's: applyGlass sets it, also when the wallpaper loads after the card opens.
                glassSurfaces.removeAll { it.get() === box.glass }
                frostedCard = java.lang.ref.WeakReference(box.glass)
                box.glass.setBlur(frostedBlur())
            }
        } else LinearLayout(this).apply {
            background = if (dark) rimCard(dp(16), false, solid = true) else GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFF7F8FA.toInt())
            }
        }
        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            // Google TV and Fire TV skip the role step, so their count starts at the service.
            val skip = if (fireTv() || googleTv()) 1 else 0
            if (step > 0) addView(label(getString(R.string.setup_step, step - skip, 3 - skip), 12f).apply { secondary(0.6f) })
            addView(label(title, 22f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
            body?.let { addView(label(it, 14f).apply { maxLines = 6; secondary(0.8f); setLineSpacing(0f, 1.15f); setPadding(0, dp(10), 0, 0) }) }
            extra?.let { addView(it) }
            buttons.forEachIndexed { i, (name, action) ->
                addView(settingsRow(name, "") { if (previewAt >= 0) previewHomeSetup(previewAt + 1) else action() },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = if (i == 0) dp(20) else dp(4) })
            }
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(if (extra != null) 600 else 480), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount((if (frost) GlassStyle.FROSTED else glassLook().active)?.dim ?: 0.6f) // glass shows what is behind, so it needs less dimming
        }
        dialog.setOnDismissListener {
            if (setupDialog !== dialog) return@setOnDismissListener
            setupDialog = null // closed with Back or a button: setup stops here, and Settings can start it again
            setupStep = 0
            homeTest = 0
            previewAt = -1
        }
        setupDialog = dialog
        dialog.show()
        card.getChildAt(card.childCount - buttons.size).requestFocus()
    }

    /** Debug builds: every popup in turn, with buttons that only move on, to check the look on a TV. */
    internal fun previewHomeSetup(at: Int = 0) {
        previewAt = at
        when (at) {
            0 -> rolePopup()
            1 -> testPopup(1)
            2 -> servicePopup()
            3 -> restrictedPopup()
            4 -> adbPopup()
            5 -> donePopup()
            else -> setupDialog?.dismiss()
        }
    }

    /** Home pressed while already home: close whatever is open and go back to the start of the apps row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep requests received before unlock or a theme recreation
        if (!ready) return
        // Home pressed in Android's settings means home, not settings; the screensaver's end comes in as Home too.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !prefs.getBoolean("return_dream", false)) prefs.edit().remove("return_settings").apply()
        if (intent.action == UPDATE_STATUS) return installStatus(intent)
        if (intent.getBooleanExtra(Remap.OPEN_ALL_APPS, false) || intent.getBooleanExtra(Remap.OPEN_RECENT_APPS, false)) return // onResume handles new and existing activities
        if (fakeSetup(intent)) return
        if (homeTest != 0 && intent.hasCategory(Intent.CATEGORY_HOME)) { // the Home press reached us: setup works
            homeTest = 0
            return donePopup()
        }
        // Nooko Launcher as Android TV's home app, without the Home service: the system sends each Home press here as an intent.
        // The service counts presses itself and sends Recent apps as an extra (handled above), so skip it when it is on.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !prefs.getBoolean("return_dream", false) && HomeOverride.running == null &&
            systemHomePress.released(SystemClock.uptimeMillis())) return openAllApps(recent = true)
        val back = returnTo // set when Nooko Launcher started the app that Home leaves
        returnTo = null
        if (editing) endEdit()
        endTopEdit()
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        // Home from an app goes back to the tile or card it was started from, wherever focus is now. If Nooko Launcher did not start the app,
        // or the tile or card is gone, Home starts again at the first tile.
        if (back?.let { homeViewFor(it) }?.requestFocus() == true) return
        watchScroll.scrollTo(0, 0)
        appRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes overlays")
    override fun onBackPressed() {
        if (!ready) return
        when {
            photoPreview != null -> finishPhotoPreview(false)
            settingsScrim.visibility == View.VISIBLE -> pageBack?.invoke() ?: closeLauncherSettings()
            allApps.visibility == View.VISIBLE && recentAppsOpen && animationsOn() && ValueAnimator.areAnimatorsEnabled() ->
                recentWindows.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(150).setInterpolator(EASE)
                    .withEndAction { recentWindows.alpha = 1f; recentWindows.scaleX = 1f; recentWindows.scaleY = 1f; if (recentAppsOpen) closeAllApps() }.start()
            allApps.visibility == View.VISIBLE -> closeAllApps()
        }
    }

    // ---------- theme ----------

    internal fun wantDark() = prefersDark(prefs) || heroFull() // the fullscreen TMDB look is a dark one

    /** The clock's digits only: the date keeps the system font, which has every language's month names. */
    internal fun clockFace(): Typeface = when (prefs.getString("clock_style", "light")) {
        "bold" -> Typeface.create("sans-serif", Typeface.BOLD) // strong on busy wallpapers
        "serif" -> resources.getFont(R.font.clock_serif) // Fraunces, OFL
        else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    internal fun clockSize() = CLOCK_SIZES.keys.first { it.toString() == prefs.getString("clock_size", "48") }

    private fun clockPattern(format: ClockFormat) = prefs.getString(format.key, null)?.takeIf(format::accepts)

    private fun applyClockFormats() {
        val time = clockPattern(ClockFormat.TIME)
        findViewById<TextClock>(R.id.clock).apply {
            format12Hour = time ?: "h:mm"
            format24Hour = time ?: "HH:mm"
        }
        val date = clockPattern(ClockFormat.DATE) ?: "EEE d MMM"
        findViewById<TextClock>(R.id.date).apply {
            format12Hour = date
            format24Hour = date
        }
    }

    private fun applyTheme() {
        dark = wantDark()
        fg = if (dark) DARK_TEXT else 0xFF111214.toInt()
        // Without glass the cards are nearly solid, so Off looks clearly different from glass: dark ones are rimCard()s
        // at 90%, light ones grey at 94%, and focus lifts them to 95% and 100%.
        // Over a blur the light tiles need less colour of their own (43% at rest, 75% selected; Clear is lighter).
        val look = glassLook()
        val glassy = look.active
        // Glass tints with the wallpaper's own colour, and focus brightens a tile without filling it white.
        cardRest = if (dark) DARK_CARD else if (glassy != null) IntArray(2) { look.tile(lit = false) } else intArrayOf(0xF0E2E5E9.toInt(), 0xF0D8DCE1.toInt())
        cardLit = if (dark) DARK_CARD_LIT else if (glassy != null) IntArray(2) { look.tile(lit = true) } else intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        ground =if (dark) DARK_BG else 0xFFF3F5F7.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(ground)
        // With glass, the wallpaper and the frost behind the grid are the point of All apps: let them show.
        allApps.setBackgroundColor(if (recentAppsOpen || glassy != null) Color.TRANSPARENT else ground)
        findViewById<TextView>(R.id.clock).typeface = clockFace()
        findViewById<TextView>(R.id.clock).textSize = clockSize().toFloat()
        applyClockFormats()
        for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.watchTitle, R.id.allAppsTitle, R.id.rowLabel, R.id.weather)) {
            findViewById<TextView>(id).setTextColor(fg)
        }
        iconInk = fg
        for (v in topIcons()) v.setColorFilter(if (v.isFocused) fg else iconInk)
        watchInk = fg
        appsInk = fg
    }

    // ---------- text over the wallpaper ----------
    // The wallpaper can be any photo, so the theme's text colour can vanish into it. A tiny copy of the wallpaper is
    // kept, and the clock block and the Continue watching text each take white or near-black, whichever stands out
    // more from the pixels right behind them. Plain wallpapers keep the theme's colour.

    internal var wpThumb: Bitmap? = null
    internal var wpFrames: List<Bitmap> = emptyList() // what the text is measured on: the still's thumb, or a video's frames
    internal var watchInk = Color.BLACK
    internal var iconInk = Color.BLACK
    private var appsInk = Color.BLACK

    private fun topIcons() = TOP_KEYS.keys.toList()
        .map { findViewById<ImageView>(it) }

    internal fun fitInk() {
        val top = listOf(R.id.clock, R.id.date, R.id.weather).map { findViewById<TextView>(it) }
        // Over the fullscreen TMDB picture the clock and the top icons are always white, whatever the wallpaper behind it is.
        val fullPicture = heroFull() && heroBackdrop.visibility == View.VISIBLE
        val topInk = if (fullPicture) Color.WHITE else inkBehind(top)
        for (v in top) ink(v, topInk, fade = true)
        val icons = topIcons()
        iconInk = if (fullPicture) Color.WHITE else inkBehind(icons)
        for (v in icons) {
            if (!v.isFocused) v.setColorFilter(iconInk)
            iconShadow(v)
        }
        val names = findViewById<LinearLayout>(R.id.nameRow).let { row -> (0 until row.childCount).map { row.getChildAt(it) as TextView } }
        val apps = listOf(findViewById<TextView>(R.id.appsTitle)) + names
        // Measured over the whole row, so the On focus name keeps one colour as it moves from tile to tile.
        appsInk = inkBehind(apps + findViewById<View>(R.id.appScroll))
        for (v in apps) ink(v, appsInk, fade = true)
        ink(findViewById(R.id.rowLabel), appsInk, fade = true)
        val cols = (0 until watchRow.childCount).map { watchRow.getChildAt(it) as ViewGroup }
        val watch = listOf(watchTitle) + cols.flatMap { col -> (0 until col.childCount).mapNotNull { col.getChildAt(it) as? TextView } }
        watchInk = inkBehind(watch)
        for (v in watch) ink(v, watchInk, fade = true)
        // The progress bar and the source mark go with the card's text.
        for (col in cols) for (i in 0 until col.childCount) when (val v = col.getChildAt(i)) {
            is ProgressBar -> v.progressDrawable = progressBarDrawable(watchInk)
            is TextView -> if (v.tag == INK_MARK) v.compoundDrawablesRelative[0]?.setTint(watchInk)
        }
    }

    /**
     * A tight contrast shadow under a top icon so it stays readable on busy photos.
     * Drawn once into a small bitmap in the icon's background, under its focus ring; none on a plain wallpaper.
     */
    private fun iconShadow(v: ImageView) {
        val layers = v.background as? LayerDrawable ?: return
        val icon = v.drawable?.constantState?.newDrawable(resources)?.mutate()
        val box = v.layoutParams.width - v.paddingLeft - v.paddingRight
        if (wpThumb == null || icon == null || box <= 0) return layers.setDrawable(0, ColorDrawable(Color.TRANSPARENT))
        val blur = dp(1).coerceAtLeast(1)
        val mask = Bitmap.createBitmap(box + blur * 2, box + blur * 2, Bitmap.Config.ARGB_8888)
        icon.setBounds(blur, blur, blur + box, blur + box)
        icon.draw(Canvas(mask))
        val shape = mask.extractAlpha()
        mask.eraseColor(Color.TRANSPARENT)
        Canvas(mask).drawBitmap(shape, 0f, dp(1).toFloat(), Paint().apply {
            color = if (iconInk == Color.WHITE) 0x2E000000 else 0x2EFFFFFF
            maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
        })
        shape.recycle()
        layers.setDrawable(0, BitmapDrawable(resources, mask).apply { alpha = if (v.isFocused) 0 else 255 })
        val inset = v.paddingLeft - blur
        layers.setLayerInset(0, inset, inset, inset, inset)
    }

    /**
     * White or near-black for text over [views], readable on every frame of the wallpaper (one for a photo, a video's
     * samples, see [inkFor]); the theme colour without a picture.
     */
    internal fun inkBehind(views: List<View>): Int {
        val t = wpFrames.firstOrNull() ?: return fg
        val wall = findViewById<View>(R.id.wallpaper)
        val at = IntArray(2)
        wall.getLocationInWindow(at)
        val (wx, wy) = at[0] to at[1]
        val r = Rect()
        for (v in views) if (v.isShown && v.width > 0) {
            v.getLocationInWindow(at)
            r.union(at[0] - wx, at[1] - wy, at[0] - wx + v.width, at[1] - wy + v.height)
        }
        if (r.isEmpty || wall.width == 0) return fg
        // The wallpaper is centre-cropped: scaled to cover the screen, centred.
        val s = maxOf(wall.width.toFloat() / t.width, wall.height.toFloat() / t.height)
        val dx = (wall.width - t.width * s) / 2
        val dy = (wall.height - t.height * s) / 2
        fun tx(x: Int) = ((x - dx) / s).toInt().coerceIn(0, t.width - 1)
        fun ty(y: Int) = ((y - dy) / s).toInt().coerceIn(0, t.height - 1)
        // Every frame has the thumb's size, so one mapping serves all of them.
        return inkFor(wpFrames.map { f ->
            val px = ArrayList<Float>()
            for (y in ty(r.top)..ty(r.bottom)) for (x in tx(r.left)..tx(r.right)) px += Color.luminance(f.getPixel(x, y))
            px.toFloatArray()
        })
    }

    /**
     * Text colour plus a shadow in the opposite colour. Nooko Launcher never darkens the picture, so on a busy one this shadow
     * is what keeps the text readable: wider and stronger than a plain drop shadow.
     */
    /** Text colour, and the same for an icon beside the text (the weather's). */
    internal fun TextView.tint(color: Int) {
        setTextColor(color)
        compoundDrawableTintList = ColorStateList.valueOf(color)
    }

    internal fun ink(v: TextView, color: Int, fade: Boolean = false) {
        if (wpThumb == null) v.setShadowLayer(0f, 0f, 0f, 0)
        // Tight and light: a wide one is cut off at the view's edges and shows as a dark box behind the text.
        else v.setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), if (color == Color.WHITE) 0x73000000 else 0x66FFFFFF)
        if (!fade || v.currentTextColor == color) return v.tint(color)
        ValueAnimator.ofArgb(v.currentTextColor, color).apply {
            duration = 800 // with the wallpaper's crossfade
            addUpdateListener { v.tint(it.animatedValue as Int) }
            start()
        }
    }

    // ---------- wallpapers ----------
    // Shown as they are: Nooko Launcher never paints over the picture, the text and the glass adapt to it instead.
    // Your own go in Android/data/dev.nooko.launcher/files/wallpapers/ (adb push, no permission needed). A folder on the TV
    // (MediaStore, so USB drives too) shows its photos in shuffled turns, one decoded at a time.
    // Decoded off the UI thread. Full colour, not RGB_565:
    // 565 rounds a smooth sky's 1-2 level JPEG ripples into visible grey and green blocks.

    internal fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** A plain choice first (just the theme's base colour, no image), then pictures. */
    internal fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            (wallpaperName(Bing.CHOICE) to Bing.CHOICE) +
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList()) +
            listOfNotNull(currentWallpaper(forDark).takeIf { it.startsWith("folder:") }?.let { wallpaperName(it) to it })

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    private fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    internal fun currentWallpaper(forDark: Boolean = dark) =
        prefs.getString(if (forDark) "wp_dark" else "wp_light", null)?.let { movedWallpaper(it, forDark) } ?: Bing.CHOICE

    /** The built-in pictures, the pack, gradients, videos and the Shows wallpaper are gone: a choice that named one falls back to Bing. */
    private fun movedWallpaper(choice: String, forDark: Boolean) =
        if (choice.startsWith("pack:") || choice.startsWith("res:") || choice.startsWith("gradient:") || choice.startsWith("video:") || choice == "shows") Bing.CHOICE else choice

    /**
     * Glass: the tiles are see-through over a blur of the wallpaper (a setting, on by default since 1.6).
     * The stored style is Frosted or Clear; the old on/off switch is read until a style is picked.
     */
    internal fun glassStyle() = GlassStyle.of(prefs.getString("glass_style", null), prefs.getBoolean("glass", true))

    /**
     * The style, the theme and what is behind the home screen: all that glass depends on. A video is never captured
     * to blur (that fails on TVs that play video on a hardware overlay, and redrawing costs frames): it gets a tint.
     */
    private fun glassLook() = GlassLook(glassStyle(), dark, Backdrop.of(currentWallpaper()), prefs.getInt("glass_hue", GLASS_GREY),
        coloured = prefs.getString("theme", null) == "wallpaper")

    /** Glass needs something behind it: with a plain background the tiles stay as they are. */
    internal fun glassActive() = glassLook().active != null

    /** The name of the current style, for the settings row. */
    internal fun glassName() = when (glassStyle()) {
        null -> R.string.off
        GlassStyle.CLEAR -> R.string.glass_clear
        else -> R.string.glass_frosted
    }

    /** The glass surfaces that are on screen, so that a wallpaper change (or a blur that arrives late) reaches them. */
    private val glassSurfaces = ArrayList<java.lang.ref.WeakReference<GlassDrawable>>()

    /** A glass surface in this theme: the blurred wallpaper under it, a tint, a hairline rim and a little light on top. */
    private fun glassSurface(round: Float, flatRight: Boolean = false): GlassBox = GlassBox(this).also { styleGlass(it.glass, round, flatRight) }

    internal fun styleGlass(g: GlassDrawable, round: Float, flatRight: Boolean = false) {
        g.round = round
        g.flatRight = flatRight
        g.ref = findViewById(R.id.root) // the wallpaper the blur shows, also for a menu in its own window
        g.wash = glassLook().wash()
        g.highlight = if (dark) 0x26FFFFFF else 0x66FFFFFF
        g.setBlur(surfaceBlur)
        glassSurfaces.removeAll { it.get() == null }
        if (glassSurfaces.none { it.get() === g }) glassSurfaces.add(java.lang.ref.WeakReference(g)) // the panel is styled again and again
    }

    /** The shown picture, sharp, for Clear's lens ([glassSharp]); null unless Clear was the style when it loaded. */
    internal var sharpPixels: Bitmap? = null

    /** The shown picture (a video's first frame) as [glassScreen] pixels, not blurred; null for a plain background. */
    internal var glassPixels: IntArray? = null

    /** A card that is Frosted whatever the Glass setting is (see [homePopup]). */
    private var frostedCard: java.lang.ref.WeakReference<GlassDrawable>? = null

    private fun frostedBlur(): Bitmap? = if (glassLook().active == GlassStyle.FROSTED) surfaceBlur
        else glassPixels?.let { Bitmap.createBitmap(glassBlur(it, GlassStyle.FROSTED.radius, dark), GLASS_W, GLASS_H, Bitmap.Config.ARGB_8888) }

    /**
     * The blur for menus, dialogs and the panel: softer than tiles so text stays readable.
     */
    private var surfaceBlur: Bitmap? = null

    /** The look and the pixels that glass was last drawn for. */
    private var glassShown: Pair<GlassLook, IntArray?>? = null

    /**
     * Brings everything glass paints in line with [glassLook] and [glassPixels]. Call it whenever the style, the theme
     * or the shown wallpaper changes; it does nothing when neither did.
     */
    internal fun applyGlass() {
        val look = glassLook()
        val px = glassPixels
        val was = glassShown
        // The layer's own values first: cheap, and a new screen (a theme change) has none yet even when the look
        // matches what its tiles were built for.
        glass.lens = look.lens
        glass.grain = look.active?.grain == true
        glass.edge = look.active?.edge ?: 0
        glass.shade = look.active?.shade ?: 0
        if (look.active != GlassStyle.CLEAR) sharpPixels = null // 2 MB, kept only for Clear
        if (was != null && was.first == look && was.second === px) return
        glassShown = look to px
        val shown = px?.takeIf { look.active != null }
        glass.setSharp(sharpPixels?.takeIf { look.active == GlassStyle.CLEAR }, io)
        glass.setBlur(shown?.let { glassBlur(it, look.active!!.radius, look.dark) })
        val frost = if (look.active == GlassStyle.CLEAR) 4 else GlassStyle.FROSTED.radius
        surfaceBlur = if (look.active?.radius == frost) glass.blur
            else shown?.let { Bitmap.createBitmap(glassBlur(it, frost, look.dark), GLASS_W, GLASS_H, Bitmap.Config.ARGB_8888) }
        glassSurfaces.forEach { it.get()?.setBlur(surfaceBlur) } // menus and cards that are open, and the panel
        frostedCard?.get()?.setBlur(frostedBlur())
        if (was?.first != look) { // the card tints and which tiles get frost follow the look
            applyTheme()
            baked.clear()
            glass.clearTiles()
            renderApps()
            watchKey = null
            showWatchNext(Cards(cardSources, cardCurrent, watchList))
        }
        showPanelGlass()
    }

    private var noteView: View? = null
    private var noteHide: Runnable? = null

    /**
     * A short message at the bottom of the screen. With Glass it is a frosted pill, which fades in and out;
     * otherwise it is the system's toast. A message shown just before another screen opens keeps the system's toast
     * ([system]): the pill would stay behind that screen and nobody would see it.
     */
    internal fun toast(text: CharSequence, long: Boolean = false, system: Boolean = false) {
        if (system || !glassActive()) return Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        val root = findViewById<FrameLayout>(R.id.root)
        noteHide?.let { ui.removeCallbacks(it) }
        noteView?.let { root.removeView(it) }
        val pill = glassSurface(dp(24).toFloat()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26), dp(13), dp(26), dp(13))
            alpha = 0f
            translationY = dp(14).toFloat()
            addView(label(text.toString(), 15f).apply {
                maxLines = 3
                ellipsize = null
                maxWidth = dp(640)
            })
        }
        root.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(34) })
        noteView = pill
        pill.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DECELERATE).start()
        noteHide = Runnable {
            pill.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(220).withEndAction { root.removeView(pill) }.start()
        }.also { ui.postDelayed(it, if (long) 3500L else 2000L) }
    }

    /** Supporting text in dark mode: dim grey on black, lighter on glass, where it sits over a photo. */
    private fun text2() = if (glassActive()) 0xFFCCCCCC.toInt() else DARK_TEXT_2

    internal fun wallpaperName(choice: String) = when {
        choice == Bing.CHOICE -> getString(R.string.bing_daily)
        choice == Wallhaven.CHOICE -> getString(R.string.wallhaven_wallpaper)
        choice.startsWith("folder:") -> getString(R.string.folder_name, choice.substringAfter(':').substringAfter(':'))
        choice.startsWith("photo:") -> getString(R.string.wall_single_photo)
        else -> choice.substringAfter(':').substringBeforeLast('.')
    }

    internal val photoWallpaper by lazy { PhotoWallpaperSurface(findViewById(R.id.photoWallpaper)) }

    @Suppress("DEPRECATION") // defaultDisplay is the fallback on Android 8 and 9.
    internal var wallpaperLoad = 0
    internal var folderPending = false
    internal var folderRetries = 0

    internal val folderRetryTask = Runnable {
        if (!isDestroyed && folderPending && currentWallpaper().startsWith("folder:")) {
            loadWallpaper()
        }
    }

    private val photoObserver by lazy {
        object : ContentObserver(ui) {
            override fun onChange(selfChange: Boolean) {
                if (!isDestroyed && folderPending && currentWallpaper().startsWith("folder:")) {
                    loadWallpaper()
                }
            }
        }
    }

    internal fun wallpaperPickerOpen() = settingsScrim.visibility == View.VISIBLE && lastSettings == R.string.wallpaper

    /** A tile in the wallpaper grid: a [choice]'s picture (for a tile that opens more, the one in use there) or an [icon]. */
    internal class Tile(val name: String, val choice: String? = null, val icon: Int? = null, val help: Int? = null,
                             val cover: Int? = null, val checked: Boolean = false, val hideLabel: Boolean = false,
                             val captionIcon: Int? = null, val detail: String? = null,
                             val action: () -> Unit)

    /** Your uploaded photos, as choices. */
    internal fun ownUploads(forDark: Boolean): List<String> = wallpaperChoices(forDark).filter { it.second.startsWith("file:") }.map { it.second }

    internal var wallPreview: String? = null
    internal var hidden = false // stopped since settings opened another screen (a trampoline only pauses us)

    /** Settings is about to open another screen: come back to row [at] after it ([dream]: the screensaver, which ends with Home). */
    private fun returnTo(at: Int, dream: Boolean = false) = prefs.edit().putInt("return_settings", at).putBoolean("return_dream", dream).apply()

    /** A picked wallpaper, shown whole: the panel and the dimming step aside until any button. */
    internal var previewBack: (() -> Unit)? = null

    internal class PhotoPreview(
        val layer: ImageView, val controls: TextView, val choices: List<String>,
        val apply: (String) -> Unit, val back: () -> Unit,
        var index: Int = 0, var shown: Boolean = false, var generation: Int = 0,
    )

    internal var photoPreview: PhotoPreview? = null

    private val bingChanged: () -> Unit = { if (currentWallpaper() == Bing.CHOICE) loadWallpaper() }
    private val wallhavenChanged: () -> Unit = { if (currentWallpaper() == Wallhaven.CHOICE) loadWallpaper() }


    private fun showKey(p: Prog) = "${p.pkg}/${p.title}/${p.image}"

    /** An app's name: from the app list when it is loaded, else from the package manager (null when the app is not there). */
    private fun appName(label: Map<String, String>, p: String): String? = label[p] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { null }

    // ---------- folder wallpapers ----------

    internal var afterPhotoAccess: (() -> Unit)? = null // what asked, while Android's permission prompt is up
    internal val deck = ArrayDeque<Long>() // photos not shown yet this round; io thread only

    internal fun photoKey(forDark: Boolean = dark) = "wp_photo_light"
    internal fun photoAtKey(forDark: Boolean = dark) = "wp_photo_at_light"
    internal fun photoEvery() = prefs.getInt("wp_every", 30).takeIf { it in PHOTO_EVERY } ?: 30

    // ---------- remote keys ----------
    // Settings > Remote keys: press a key on the remote to pick it, then choose what it does. The work is done by
    // the Home key service (see Remap), which is why the page asks for it to be on.

    internal fun keysSummary() = Remap.all(this).size.let { if (it == 0) getString(R.string.key_unset) else resources.getQuantityString(R.plurals.n_keys, it, it) }

    /** While the home screen shows, a folder wallpaper moves on to its next photo when its time is up. */
    internal val wallpaperTick = object : Runnable {
        override fun run() {
            if (currentWallpaper() == Bing.CHOICE || currentWallpaper() == Wallhaven.CHOICE) {
                Bing.refresh(applicationContext, prefs)
                Wallhaven.refresh(applicationContext, prefs)
                ui.postDelayed(this, 60_000L)
                return
            }
            val every = photoEvery() * 60_000L
            if (!currentWallpaper().startsWith("folder:")) return
            val wait = prefs.getLong(photoAtKey(), 0) + every - System.currentTimeMillis()
            if (wait <= 0) nextPhoto()
            ui.postDelayed(this, if (wait <= 0) every else wait)
        }
    }

    internal fun savedPhotoSelection(): Set<Long> =
        prefs.getStringSet("wp_selected_photos", emptySet()).orEmpty().mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }

    internal var upload: UploadServer? = null

    /** The TV's own address on the home network (IPv4: easy to read and type). */
    internal fun lanAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Whole pixels per module and at least four clear modules around the code. */
    internal fun qrBitmap(text: String, maxPx: Int, rounded: Boolean = false): Bitmap {
        val (m, fixed) = Qr.symbol(text)
        val margin = if (rounded) 6 else 4
        val n = m.size + margin * 2
        val px = maxOf(1, maxPx / n)
        val size = n * px
        val bmp = Bitmap.createBitmap(size, size, if (rounded) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (rounded) {
            paint.color = 0xFFF4F8FF.toInt()
            val radius = px * 3f
            canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, paint)
            paint.color = 0xFF0D2540.toInt()
        } else {
            canvas.drawColor(Color.WHITE)
            paint.color = Color.BLACK
        }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            val x = ((c + margin) * px).toFloat()
            val y = ((r + margin) * px).toFloat()
            if (rounded && !fixed[r][c]) {
                val radius = px * .28f
                canvas.drawRoundRect(x, y, x + px, y + px, radius, radius, paint)
            } else {
                canvas.drawRect(x, y, x + px, y + px, paint)
            }
        }
        return bmp
    }

    // ---------- launcher settings panel (top-bar button; the Settings tile is Android's) ----------

    internal val settingsScrim by lazy { findViewById<View>(R.id.settingsScrim) }
    internal val settingsList by lazy { findViewById<LinearLayout>(R.id.settingsList) }

    internal var panelFrom: View? = null // focus to go back to when the panel closes

    internal var lastSettings = 0 // section used last, focused when settings open again

    internal fun clockFormatRow(title: Int, format: ClockFormat) = page(getString(title),
        clockPattern(format)?.let { DateFormat.format(it, System.currentTimeMillis()).toString() }
            ?: getString(R.string.clock_format_default)) { pickClockFormat(title, format) }

    private fun saveClockFormat(title: Int, format: ClockFormat, pattern: String?) {
        prefs.edit().putString(format.key, pattern).apply()
        applyClockFormats()
        findViewById<View>(R.id.clock).post { fitInk() }
        renderSettings(title)
    }

    private fun pickClockFormat(title: Int, format: ClockFormat, focus: Int? = null) {
        val selected = clockPattern(format)
        val patterns = listOf(null) + format.presets
        val example = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 23, 17, 5, 9) }
        val rows = patterns.map { pattern ->
            page(pattern?.let { DateFormat.format(it, example).toString() } ?: getString(R.string.clock_format_default),
                if (pattern == selected) CHOSEN else "") { saveClockFormat(title, format, pattern) }
        } + page(getString(R.string.clock_format_custom), if (selected != null && selected !in format.presets) CHOSEN else "") {
            editClockFormat(title, format, selected ?: format.presets.first())
        }
        val selectedRow = patterns.indexOf(selected).takeIf { it >= 0 } ?: rows.lastIndex
        showPage(getString(title), rows, focus ?: selectedRow, crumb = getString(R.string.section_clock_weather)) {
            renderSettings(title)
        }
    }

    private fun editClockFormat(title: Int, format: ClockFormat, pattern: String) {
        fun save(value: String, field: EditText) {
            if (!format.accepts(value)) return
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
            saveClockFormat(title, format, value)
        }
        val preview = label("", 14f).apply { maxLines = 3; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val hint = getString(if (format == ClockFormat.DATE) R.string.date_format_hint else R.string.time_format_hint)
        lateinit var field: EditText
        field = textField(pattern, hint, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_DONE) { save(it, field) }
        field.filters = arrayOf(InputFilter.LengthFilter(ClockFormat.MAX_LENGTH))
        fun updatePreview(value: String) {
            preview.text = if (format.accepts(value)) getString(R.string.clock_format_preview, DateFormat.format(value, System.currentTimeMillis()))
                else getString(R.string.clock_format_invalid, hint)
        }
        updatePreview(pattern)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview(s.toString().trim())
            override fun afterTextChanged(s: Editable?) {}
        })
        val top = (field.parent as LinearLayout).apply {
            val help = if (format == ClockFormat.DATE) R.string.date_format_tokens else R.string.time_format_tokens
            addView(label(getString(help), 12f).apply { maxLines = 3; setPadding(dp(14), dp(8), dp(14), 0) })
            addView(preview)
        }
        showPage(getString(R.string.clock_format_custom), listOf(page(getString(R.string.done)) { save(field.text.toString().trim(), field) }),
            top = top, crumb = getString(title)) { pickClockFormat(title, format, format.presets.size + 1) }
        val done = settingsList.getChildAt(1)
        done.nextFocusUpId = field.id
        field.nextFocusDownId = done.id
        field.requestFocus()
    }

    /** What Auto does here today, under Wallpaper & theme. Times in the TV's 12- or 24-hour format. */
    internal fun autoNote(): String {
        val time = DateFormat.getTimeFormat(this)
        val place = sunPlace(prefs) ?: return getString(R.string.auto_note_clock,
            time.format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 0) }.time),
            time.format(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0) }.time))
        val city = prefs.getString("geo_city", null).orEmpty().ifEmpty { getString(R.string.your_area) }
        // Today's noon, not now: just after midnight, now's nearest sun day is still yesterday's.
        val noon = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0) }.timeInMillis
        return when (val d = Sun.day(place.first, place.second, noon)) {
            Sun.Day.AlwaysUp -> getString(R.string.auto_note_up, city)
            Sun.Day.AlwaysDown -> getString(R.string.auto_note_down, city)
            is Sun.Day.Times -> getString(R.string.auto_note_sun, city, time.format(Date(d.rise)), time.format(Date(d.set)))
        }
    }

    internal fun <T> List<T>.step(current: T, dir: Int) = this[((indexOf(current) + dir) % size + size) % size]

    // Pages inside the settings panel (wallpaper list, app toggles…) instead of system dialogs, so they
    // match Nooko Launcher's look. Back goes up one page; from the main list it closes the panel.

    internal var pageBack: (() -> Unit)? = null
    internal var pageExit: (() -> Unit)? = null // cleanup when the page goes away (stops the upload server)

    internal fun page(title: String, value: String = "", action: () -> Unit) = Triple(title, value, action)

    // ---------- layout lock ----------
    // A PIN in front of Nooko Launcher settings, the Wi-Fi and Android settings icons, and everything that changes the home
    // screen; apps still open as usual. It keeps the layout safe from small hands, it doesn't keep apps from kids:
    // voice search and the remote's Settings button get past it, so it's called a layout lock, not parental controls.

    internal fun lockOn() = prefs.contains("lock_pin")

    internal var unlockedUntil = 0L // one PIN covers a few minutes of changes, until the home screen is left
    internal var pinFails = 0
    internal var pinWaitUntil = 0L // ponytail: in memory, so restarting Nooko Launcher resets the wait; fine for kids at a remote
    internal var pinBox: View? = null

    internal fun locked() = lockOn() && SystemClock.elapsedRealtime() > unlockedUntil

    internal fun setLock(): Unit = pinPage(getString(R.string.choose_pin), { renderSettings(R.string.lock_layout) }) { first ->
        pinPage(getString(R.string.enter_again), { renderSettings(R.string.lock_layout) }) { again ->
            if (again != first) {
                toast(getString(R.string.pins_mismatch), long = true)
                setLock()
                return@pinPage null
            }
            prefs.edit().putString("lock_pin", hashPin(first)).apply()
            unlockedUntil = SystemClock.elapsedRealtime() + UNLOCKED_MS // no PIN straight away for the one who set it
            renderSettings(R.string.lock_layout)
            toast(getString(R.string.layout_locked), long = true)
            null
        }
        null
    }

    /** A settings row; with [step], Left and Right change its value in place, and ‹ › show that while focused. */
    internal val pages = ArrayList<String>() // crumb and title of the settings pages open, from the main list down
    internal val spotlight by lazy { Spotlight() }

    /**
     * The settings panel's dim over the home screen, with a soft window over [area]'s part of it: the part the
     * selected section changes. It glides from part to part; null dims evenly. Animators follow the TV's animation scale.
     */
    internal inner class Spotlight : Drawable() {
        private val hole = RectF()
        private var open = 0f // 0: even dim, 1: the window fully clear
        private var anim: ValueAnimator? = null
        private val path = Path()

        fun reset() {
            anim?.cancel()
            open = 0f
            invalidateSelf()
        }

        fun show(target: RectF?) {
            anim?.cancel()
            val from = RectF(hole)
            val fromOpen = open
            // Coming from an even dim, the window opens in place rather than flying in from the last spot.
            if (target != null && open == 0f) from.set(target)
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = DECELERATE
                addUpdateListener { a ->
                    val t = a.animatedValue as Float
                    if (target != null) hole.set(from.left + (target.left - from.left) * t, from.top + (target.top - from.top) * t,
                        from.right + (target.right - from.right) * t, from.bottom + (target.bottom - from.bottom) * t)
                    open = fromOpen + ((if (target == null) 0f else 1f) - fromOpen) * t
                    invalidateSelf()
                }
                start()
            }
        }

        private val scrimAlpha get() = glassLook().active?.scrim ?: SCRIM_ALPHA

        override fun draw(c: Canvas) {
            val dim = scrimAlpha shl 24
            if (open == 0f) return c.drawColor(dim)
            path.reset()
            path.addRoundRect(hole, dp(16).toFloat(), dp(16).toFloat(), Path.Direction.CW)
            c.save()
            c.clipOutPath(path)
            c.drawColor(dim)
            c.restore()
            c.save()
            c.clipPath(path)
            c.drawColor((scrimAlpha * (1 - open)).toInt() shl 24) // the window clears as it opens
            c.restore()
        }

        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Where [area] sits on the screen, with some room around it; null when it has no part on screen. */
    private fun areaBounds(area: Int?): RectF? {
        val views = when (area) {
            // Past the edges by the window's corner radius, so the round corners fall off screen and no corner stays dim.
            AREA_ALL -> return RectF(0f, 0f, settingsScrim.width.toFloat(), settingsScrim.height.toFloat()).apply { inset(-dp(16).toFloat(), -dp(16).toFloat()) }
            AREA_CLOCK -> listOf(findViewById<View>(R.id.clock).parent as View)
            AREA_APPS -> listOf(findViewById(R.id.appScroll))
            // "On Down" hides the row by sliding it out, still VISIBLE, so isShown below can't tell.
            AREA_SHOWS -> if (rowShow() == "down" && !rowOut) return null
                else listOf(findViewById(R.id.watchTitle), findViewById<View>(R.id.watchScroll))
            else -> return null
        }.filter { it.isShown && it.height > 0 }
        if (views.isEmpty()) return null
        val at = IntArray(2)
        settingsScrim.getLocationInWindow(at)
        val (ox, oy) = at[0] to at[1]
        return views.map { v -> v.getLocationInWindow(at); RectF((at[0] - ox).toFloat(), (at[1] - oy).toFloat(), (at[0] - ox + v.width).toFloat(), (at[1] - oy + v.height).toFloat()) }
            .reduce { a, b -> a.apply { union(b) } }.apply { inset(-dp(14).toFloat(), -dp(10).toFloat()) }
    }

    /**
     * Extras for a settings row: an [icon] in front (outlined, filled while focused), a second line ([sub]), [choices]
     * side by side or drawn as small pictures ([art]), a wallpaper picture ([thumb]), a [help] line and the home [area]
     * it changes. [ext] marks a row that opens Android's settings, [header] a group's title and [hero] the big
     * wallpaper card at the top of the main list.
     */
    internal class RowStyle(val icon: Int? = null, val sub: String? = null, val choices: List<Pair<String, Int?>>? = null,
                           val thumb: Pair<String, Boolean>? = null, val help: String? = null, val area: Int? = null,
                           val art: String? = null, val ext: Boolean = false, val header: Boolean = false, val hero: Boolean = false,
                           val dim: String? = null, val logo: String? = null)

    // Settings colours, one set for each surface (dark or light, solid or glass). Over glass the text is a little
    // stronger and has a soft shadow, as the wallpaper's colour shows through the panel. Focus is always the brightest.
    internal fun ink(a: Float) = (fg and 0xFFFFFF) or ((a * 255).toInt() shl 24)
    internal fun restInk() = ink(if (dark) (if (glassActive()) .9f else .82f) else (if (glassActive()) .86f else .8f))
    internal fun valueInk(focused: Boolean = false) =
        if (focused) ink(if (dark) .85f else .8f) else ink(if (glassActive()) (if (dark) .68f else .64f) else .56f)
    internal fun focusInk() = if (dark) Color.WHITE else fg
    internal fun iconInk(focused: Boolean) = if (focused) focusInk() else ink(if (dark) (if (glassActive()) .8f else .7f) else .65f)

    /** The plate under the focused row: a white veil in dark, a white card in light (its shadow is the view's elevation). */
    private fun plate(radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(if (dark) (if (glassActive()) 0x2EFFFFFF else 0x1FFFFFFF) else if (glassActive()) 0xC7FFFFFF.toInt() else Color.WHITE)
        if (dark) setStroke(1, 0x14FFFFFF) // 1px, a hairline that catches the light
    }

    internal fun TextView.panelShadow() {
        if (glassActive()) setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), if (dark) 0x73000000 else 0x59FFFFFF)
    }

    /** The filled twin of an outlined Material Symbols icon (ms_x has ms_x_fill), or the icon itself. */
    internal fun filled(res: Int) =
        resources.getIdentifier(resources.getResourceEntryName(res) + "_fill", "drawable", packageName).takeIf { it != 0 } ?: res

    internal fun icon(res: Int, size: Int, color: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** A settings group's title, like "Home screen". Not focusable. */
    internal fun groupTitle(text: String) = label(text, 13f).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(valueInk())
        panelShadow()
        setPadding(dp(16), dp(18), dp(16), dp(4))
    }

    /** Focus fades the plate in and lights the row; the row's own parts restyle themselves in [onFocus]. */
    internal fun panelFocus(v: View, radius: Int, help: String?, area: Int?, onFocus: (Boolean) -> Unit) {
        val lit = plate(radius)
        v.isFocusable = true
        v.isClickable = true
        v.setOnFocusChangeListener { _, focused ->
            v.background = if (focused) lit else null
            if (focused) ValueAnimator.ofInt(0, 255).apply {
                duration = 140
                interpolator = DECELERATE
                addUpdateListener { lit.alpha = it.animatedValue as Int }
            }.start()
            if (!dark) { // the white card needs a shadow to stand off the near-white panel
                v.outlineProvider = ViewOutlineProvider.BACKGROUND
                v.elevation = if (focused) dp(3).toFloat() else 0f
            }
            onFocus(focused)
            if (focused) {
                showHelp(help)
                // Posted: when the panel first opens, the scrim has no size yet in this frame.
                settingsScrim.post { if (v.hasFocus()) spotlight.show(areaBounds(area)) }
            }
        }
    }

    internal fun arrow(res: Int, color: Int) = getDrawable(res)!!.mutate().apply { setBounds(0, 0, dp(18), dp(18)); setTint(color) }

    /** The wallpaper as a big picture at the top of the main list, its name under it: what people change most. */
    internal fun heroRow(title: String, name: String, style: RowStyle, action: () -> Unit): View {
        val (choice, forDark) = style.thumb!!
        val t = label(title, 16f).apply { setTextColor(restInk()); panelShadow() }
        val n = label(name, 13f).apply { setTextColor(valueInk()); panelShadow() }
        val chevron = icon(R.drawable.ms_chevron_right, 22, valueInk())
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            addView(wallpaperThumb(choice, forDark, big = true), LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(4), 0)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(t)
                    addView(n)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(chevron)
            }, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { action() }
            panelFocus(this, 18, style.help, style.area) { f ->
                t.setTextColor(if (f) focusInk() else restInk())
                n.setTextColor(valueInk(f))
                chevron.setColorFilter(valueInk(f))
            }
        }
    }

    /**
     * Every choice at once. The picked one has an outline and a light tint, never a solid fill, so the focused row stays
     * the brightest thing on the panel. Left and Right still step through them. Returns the view and its focus restyle.
     */
    internal fun choices(options: List<Pair<String, Int?>>, picked: String, stacked: Boolean = false, dim: String? = null): Pair<View, (Boolean) -> Unit> {
        val pills = options.map { (text, iconRes) ->
            label(text, 14f).apply {
                if (text == dim) alpha = .35f // there but not available here (Clear on a video)
                gravity = Gravity.CENTER
                // Under the title the choices share the row's width, icon above name, so four of them still fit.
                if (stacked) { maxLines = 2; setPadding(dp(4), dp(6), dp(4), dp(6)) } else setPadding(dp(10), dp(5), dp(10), dp(5))
                panelShadow()
                if (text == picked) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                iconRes?.let { res ->
                    val d = getDrawable(if (text == picked) filled(res) else res)!!.mutate().apply { setBounds(0, 0, dp(if (stacked) 20 else 16), dp(if (stacked) 20 else 16)) }
                    if (stacked) setCompoundDrawablesRelative(null, d, null, null) else setCompoundDrawablesRelative(d, null, null, null)
                    compoundDrawablePadding = dp(if (stacked) 4 else 5)
                }
            }
        }
        val box = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            pills.forEachIndexed { i, p ->
                addView(p, LinearLayout.LayoutParams(if (stacked) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, if (stacked) 1f else 0f)
                    .apply { if (i > 0) marginStart = dp(4) })
            }
        }
        val line = (resources.displayMetrics.density * 1.5f).toInt()
        val paint = { focused: Boolean ->
            pills.forEachIndexed { i, p ->
                val on = options[i].first == picked
                val c = if (on) (if (focused) focusInk() else restInk()) else valueInk(focused)
                p.setTextColor(c)
                p.compoundDrawablesRelative.filterNotNull().forEach { it.setTint(c) }
                val accent = glassLook().accent()
                p.background = if (!on) null else GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(accent?.let { it and 0xFFFFFF or ((if (focused) 0x4D else 0x33) shl 24) } ?: ink(if (focused) .16f else .09f))
                    setStroke(line, accent ?: ink(if (focused) .85f else .45f))
                }
            }
        }
        paint(false)
        return box to paint
    }

    /** Choices drawn as small pictures of what they do ([OptionArt]), each with its name under it. */
    internal fun artChoices(kind: String, options: List<Pair<String, Int?>>, picked: String): Pair<View, (Boolean) -> Unit> {
        val cells = options.mapIndexed { i, (text, _) ->
            Triple(OptionArt(kind, i, resources.displayMetrics.density), label(text, 13f).apply { gravity = Gravity.CENTER; panelShadow() }, text == picked)
        }
        val box = LinearLayout(this).apply {
            for ((art, name, _) in cells) addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(View(context).apply { background = art }, LinearLayout.LayoutParams(dp(76), dp(42)))
                addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val paint = { focused: Boolean ->
            for ((art, name, on) in cells) {
                art.ink = fg
                art.on = on
                art.focused = focused
                art.invalidateSelf()
                name.setTextColor(if (on) (if (focused) focusInk() else restInk()) else valueInk(focused))
                name.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            }
        }
        paint(false)
        return box to paint
    }

    /** A small picture of a wallpaper (plain ones are their colour), decoded small off the UI thread; [big] for the hero card. */
    internal fun wallpaperThumb(choice: String, forDark: Boolean, big: Boolean = false) = ImageView(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(if (big) 14 else 8).toFloat()
            setColor(if (choice.startsWith("plain:")) (if (forDark) DARK_BG else 0xFFF3F5F7.toInt()) else ink(.08f))
            setStroke(1, ink(.16f))
        }
        clipToOutline = true
        if (!choice.startsWith("plain:")) {
            // A picture icon while it loads, and if it can't (Bing not downloaded yet, file gone).
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setImageResource(R.drawable.ms_wallpaper)
            setColorFilter(ink(.4f))
        }
        // Fixed pictures keep their small copy, so a grid opened again shows them at once; Bing, shows and folders change.
        val key = "$choice|$big".takeIf { listOf("file:", "photo:").any { choice.startsWith(it) } }
        val cached = key?.let { thumbCache.get(it) }
        if (cached != null) {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(0, 0, 0, 0)
            clearColorFilter()
            setImageBitmap(cached)
        } else if (!choice.startsWith("plain:")) io.execute {
            val bmp = key?.let { thumbCache.get(it) } ?: try {
                // 1920 px wide comes out 120 for a row, 480 for the hero card
                val small = BitmapFactory.Options().apply { inSampleSize = if (big) 4 else 16 }
                when {
                    choice == Bing.CHOICE -> BitmapFactory.decodeFile(Bing.file(this@MainActivity).path, small)
                    choice == Wallhaven.CHOICE -> BitmapFactory.decodeFile(Wallhaven.file(this@MainActivity).path, small)
                    choice.startsWith("file:") -> BitmapFactory.decodeFile(File(wallpaperDir(), choice.removePrefix("file:")).path, small)
                    choice.startsWith("photo:") -> choice.substringAfter(':').toLongOrNull()?.let { mediaPhotoThumb(it, big) }
                    choice.startsWith("folder:") -> {
                        val ids = folderPhotos(contentResolver, choice)
                        val active = choice == currentWallpaper(forDark)
                        val selected = if (active) savedPhotoSelection() else emptySet()
                        val id = prefs.getLong(photoKey(forDark), -1).takeIf { active && it in ids }
                            ?: ids.firstOrNull { selected.isEmpty() || it in selected }
                        id?.let { mediaPhotoThumb(it, big) }
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null && key != null) thumbCache.put(key, bmp)
            if (bmp != null) ui.post {
                if (isDestroyed) return@post
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
                clearColorFilter()
                setImageBitmap(bmp)
            }
        }
    }

    internal fun showHelp(text: String?) = findViewById<TextView>(R.id.settingsHelp).apply {
        this.text = text
    }

    internal fun toggle(on: Boolean) = if (on) TOGGLE_ON else TOGGLE_OFF

    /** On/off: a filled track with a big knob right when on, an outline with a small knob left when off. */
    internal fun switch(on: Boolean): Pair<View, (Boolean) -> Unit> {
        val knob = View(this)
        val track = FrameLayout(this).apply {
            addView(knob, FrameLayout.LayoutParams(dp(if (on) 18 else 12), dp(if (on) 18 else 12), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
                marginStart = dp(6)
                marginEnd = dp(3)
            })
        }
        val paint = { focused: Boolean ->
            val c = if (on) (if (focused) focusInk() else restInk()) else valueInk(focused)
            track.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (on) setColor(glassLook().accent() ?: c) else setStroke(dp(2), c)
            }
            knob.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (!on) c else if (dark) Color.BLACK else Color.WHITE)
            }
        }
        paint(false)
        return track to paint
    }

    private fun showNet(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        iconShadow(wifi) // the shadow takes the new picture's shape
        // Dim only the icon: the focus ring is the background and should stay full strength.
        wifi.imageAlpha = if (caps == null) 90 else 255
    }

    internal fun openSettings(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action, data))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- apps row ----------

    internal fun loadApps(): List<App> {
        val pm = packageManager
        fun query(category: String) = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .map { it.activityInfo to category }
        return preferTvApps(query(Intent.CATEGORY_LEANBACK_LAUNCHER), query(Intent.CATEGORY_LAUNCHER)) { it.first.packageName }
            .filter { (a, _) -> a.packageName != packageName && a.packageName != TV_SETTINGS }
            .map { (a, category) ->
                App(
                    a.packageName,
                    a.loadLabel(pm).toString(),
                    Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(ComponentName(a.packageName, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    a.loadIcon(pm),
                    load = { a.loadIcon(pm) },
                )
            }
    }

    /**
     * One entry per HDMI port, keyed by the port's input id. A device that names itself over CEC (the PS5 shows up
     * as "PlayStation 5") takes over its port's entry; it's listed while asleep too, and opening it wakes it.
     * A port without one is named as in TV settings ("HDMI 2") and says so when it has no signal.
     */
    internal fun hdmiInputs(): List<App> = try {
        val tv = getSystemService(TvInputManager::class.java)
        val inputs = tv.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && !it.isHidden(this) }
        val devices = inputs.filter { it.parentId != null }
        val audio = devices.filter { isAudioDevice(it.loadLabel(this).toString()) }.map { it.parentId }.toSet()
        // Devices first, so distinctBy keeps the device over its port.
        (devices + inputs.filter { it.parentId == null }).filter { (it.parentId ?: it.id) !in audio }.map { input ->
            val port = input.parentId ?: input.id
            val named = input.parentId != null
            val label = (if (named) input.loadLabel(this) else input.loadCustomLabel(this) ?: input.loadLabel(this)).toString()
            App(
                HDMI + port,
                label,
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                getDrawable(HDMI_ICONS[hdmiIcon(label, named)]!!)!!,
                offline = !named && tv.getInputState(port) != TvInputManager.INPUT_STATE_CONNECTED,
            )
        }.distinctBy { it.pkg }
    } catch (e: Exception) {
        emptyList()
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) = refreshApps()
        override fun onInputRemoved(id: String) = refreshApps()
        override fun onInputStateChanged(id: String, state: Int) = refreshApps()
    }

    /** HDMI inputs pinned to the home row by hand (hold OK in the Sources list): shown with or without a signal. */
    internal fun pinnedInputs() = prefs.getStringSet("inputs_pinned", emptySet())!!

    /** The apps, and the inputs pinned to the row: the entries the row and All apps are made of. */
    internal fun listed(): List<App> = apps + sources.filter { it.pkg in pinnedInputs() }

    /** Once: after an update, the inputs in the row stay pinned, and the inputs drop out of row_hidden, where only apps go now. */
    internal fun moveInputsToSources(inputs: List<App>) {
        val hidden = rowHiddenSet()
        val info = packageManager.getPackageInfo(packageName, 0)
        val pins = if (info.firstInstallTime == info.lastUpdateTime) pinnedInputs() // a new install: no tile to keep
            else pinsAfterSourcesUpdate(inputs.map { it.pkg to it.offline }, hidden, pinnedInputs())
        prefs.edit()
            .putStringSet("inputs_pinned", pins)
            .putStringSet("row_hidden", hidden.filterNot { it.startsWith(HDMI) }.toSet())
            .putBoolean("inputs_moved", true)
            .apply()
    }

    private fun togglePin(src: App) {
        val pinned = pinnedInputs()
        val on = src.pkg !in pinned
        prefs.edit().putStringSet("inputs_pinned", if (on) pinned + src.pkg else pinned - src.pkg).apply()
        renderApps()
        Toast.makeText(this, getString(if (on) R.string.input_added else R.string.input_removed), Toast.LENGTH_SHORT).show()
    }

    internal fun favSortUsage() = settings.favSortUsage

    /**
     * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
     * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
     */
    internal fun rowAlign() = prefs.getString("row_align", "left")!!.takeIf { it in ROW_ALIGN } ?: "left"

    /**
     * A row shorter than the screen sits left, centred or right. A longer row always starts at the left:
     * centred, the scroller would push its first tiles out of reach.
     */
    internal fun alignRow() {
        val content = appRow.parent as View
        (content.layoutParams as FrameLayout.LayoutParams).gravity =
            if (appTileCount() > rowTiles()) Gravity.START else ROW_ALIGN[rowAlign()]!!.second
        content.requestLayout()
    }

    /** Home row names: "focus" (under the focused tile only), "always" (in every tile) or "never". */
    internal fun rowNames() = prefs.getString("names", "focus")!!

    /** "mono", "colour" or "banner". The old on/off "colour_icons" decides until a style has been picked. */
    internal fun iconStyle() = iconStyleOf(prefs.getString("icon_style", null), prefs.getBoolean("colour_icons", false))

    internal fun colourIcons() = iconStyle().let { it == "colour" || it == "banner" } // a monochrome banner row keeps the marks grey too

    /** A brand or app colour made deep enough to carry a white mark. */
    internal fun deepen(c: Int) = Color.rgb((Color.red(c) * 0.6f).toInt(), (Color.green(c) * 0.6f).toInt(), (Color.blue(c) * 0.6f).toInt())

    /** "standard", "large" or "largest": how big the home row's tiles and banners are. */
    internal fun rowSize() = prefs.getString("row_size", "standard")!!.takeIf { it in ROW_SIZES } ?: "standard"

    internal fun rowTiles() = ROW_SIZES.getValue(rowSize()).second

    /** Width of a home row tile: [across] of them fill the screen, eight at Standard size. */
    internal fun rowTileWidth(across: Int = rowTiles()) = (resources.displayMetrics.widthPixels - dp(96) - dp(GRID_GAP) * (across - 1)) / across

    /** How much bigger the row's tiles are than at Standard size: their height and the space around the mark grow with it. */
    internal fun rowScale() = rowTileWidth().toFloat() / rowTileWidth(ROW_TILES)

    /**
     * Apple TV style: the app's own banner fills the tile, rounded. Only when the banner is at least as wide as a
     * Standard-size tile, or it would be upscaled and soft (4K); null then, and the tile shows the app's mark as usual.
     * Large and Largest stretch a banner of that width a little (most are 320 px, Largest is ~400 px on a 1080p TV).
     */
    internal fun appBanner(a: App): Drawable? =
        AppPictures.read(this, a.pkg, banner = true) ?: try {
            packageManager.getActivityBanner(a.launch)
        } catch (e: Exception) {
            null
        }

    /** "App names: Always": a name under every tile, same width and gap as the tiles, so they line up. */
    internal fun showNames() {
        val row = findViewById<LinearLayout>(R.id.nameRow)
        val names = rowNames()
        val always = names == "always"
        row.visibility = if (always) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowLabel).visibility = if (names == "focus") View.VISIBLE else View.GONE
        row.removeAllViews()
        if (!always) return
        for (i in 0 until appRow.childCount) {
            val t = appRow.getChildAt(i)
            row.addView(fittingLabel(t.contentDescription.toString(), 13f).apply {
                gravity = Gravity.CENTER
                alpha = if (dark) 0.85f else 1f
                ink(this, appsInk)
            }, LinearLayout.LayoutParams(t.layoutParams as LinearLayout.LayoutParams).apply { height = MATCH_PARENT })
        }
        row.post { fitInk() } // measured again over the names' own place on the wallpaper
    }

    /** Slides the shared name label under the focused row tile; fades it out once focus leaves the row. */
    internal fun showRowLabel(v: View, focused: Boolean) {
        val label = findViewById<FittingLabel>(R.id.rowLabel)
        if (rowNames() == "always") { // the names under every tile: only the selected tile's name scrolls, if it is too wide
            val names = findViewById<LinearLayout>(R.id.nameRow)
            val at = appRow.indexOfChild(v)
            for (i in 0 until names.childCount) (names.getChildAt(i) as? FittingLabel)?.setActive(focused && !editing && i == at)
        }
        if (focused && !editing && rowNames() == "focus") {
            // Name and place change together, or moving quickly shows the next name under the previous tile for a frame.
            // Posted only on first render, when focus lands before layout and every width is still 0. A post that runs
            // after focus moved on is skipped, or it would show this old name while a newer tile is selected.
            val place = {
                label.text = v.contentDescription
                label.box = v.width // the name stays inside its tile: when it is wider it scrolls
                label.translationX = v.left + v.width / 2f - label.width / 2f
                label.setActive(true)
                ink(label, appsInk) // the row's colour (see fitInk): one colour, not a new one under each tile
            }
            if (v.width > 0 && label.width > 0) place() else v.post { if (v.isFocused) place() }
            label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
        } else {
            ui.post {
                if (currentFocus?.parent !== appRow || editing) {
                    label.animate().alpha(0f).setDuration(120).start()
                    label.setActive(false)
                }
            }
        }
    }

    /** White card: logo on top, name underneath. With no label the mark sits alone, centred, at one size. */
    internal fun tile(label: String?, icon: Drawable, mono: Boolean, tag: String, wordmarkInset: Int? = null, tint: Int? = null, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val img = ImageView(this).apply {
            setImageDrawable(icon.mutate())
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (mono) setColorFilter(tint ?: fg)
        }
        val text = label?.let {
            fittingLabel(it, 13f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), 0)
            }
        }
        // Same card as light mode; in dark the marks rest slightly dimmed and brighten with focus.
        val restLogo = if (dark) 0.8f else 1f
        val restText = if (dark) 0.65f else 1f
        img.alpha = restLogo
        text?.alpha = restText
        style(col, dp(8), card = true) { focused ->
            text?.setActive(focused) // a name too wide for the tile scrolls while it is selected
            if (!dark) return@style
            img.animate().alpha(if (focused) 1f else restLogo).setDuration(160).start()
            text?.animate()?.alpha(if (focused) 1f else restText)?.setDuration(160)?.start()
        }
        if (text == null) {
            // Icons share one height. Wordmarks sit in a square viewport, so fit them by width instead
            // (crop the empty top and bottom) or they shrink to a sliver.
            if (wordmarkInset != null) {
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setPadding(wordmarkInset, 0, wordmarkInset, 0)
            } else {
                val s = rowScale() // only home row tiles have no name: All apps keeps its one size
                val v = ((if (isBannerStyle(iconStyle())) dp(8) else dp(20)) * s).toInt() // banner rows are shorter
                val h = (dp(16) * s).toInt()
                img.setPadding(h, v, h, v)
            }
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(26)))
            col.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        col.tag = tag
        col.setOnClickListener { onClick() }
        return col
    }

    /**
     * A mono mark from an adaptive icon's foreground layer (its symbol, without the coloured backplate),
     * cropped to the safe zone. Brightness becomes opacity: the light symbol becomes the mark, and dark
     * details in it (a note inside a circle) become holes, so the mono tint keeps them. Null for old
     * single-layer icons and for photo-like layers that fill the square, which keep the greyscale look.
     */
    private fun glyph(icon: Drawable): Bitmap? {
        val fg = (icon as? AdaptiveIconDrawable)?.foreground ?: return null
        val size = dp(96) // large, so a small symbol is still sharp after trimming and scaling up
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Layers are 108dp with the symbol inside the middle 72dp; drawing at 1.5x crops to that.
        fg.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
        fg.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        if (px.count { Color.alpha(it) > 200 } > px.size * 0.7) return null // a picture, not a symbol
        val alpha = FloatArray(px.size) { i ->
            val c = px[i]
            Color.alpha(c) * (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }
        val peak = alpha.max().takeIf { it > 0f } ?: return null
        // Stretch so the brightest part is fully opaque: an orange symbol shouldn't look dimmer than a white one.
        for (i in px.indices) px[i] = Color.argb((alpha[i] * 255f / peak).toInt().coerceAtMost(255), 255, 255, 255)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        // Trim to the visible mark (as a square, centred), so small symbols fill the tile like the logos do.
        val on = px.indices.filter { Color.alpha(px[it]) > 40 }.ifEmpty { return null }
        val l = on.minOf { it % size }; val r = on.maxOf { it % size }
        val t = on.minOf { it / size }; val b = on.maxOf { it / size }
        val side = maxOf(r - l, b - t) + 1
        val x = ((l + r + 1 - side) / 2).coerceIn(0, size - side)
        val y = ((t + b + 1 - side) / 2).coerceIn(0, size - side)
        return Bitmap.createBitmap(bmp, x, y, side, side)
    }

    /** glyph() walks every pixel, too slow to redo on each row rebuild; kept per app until the app changes. */
    internal fun cachedGlyph(a: App): Drawable? {
        val bmp = if (a.pkg in GLYPHS) GLYPHS[a.pkg] else glyph(a.icon).also { GLYPHS[a.pkg] = it }
        return bmp?.let { BitmapDrawable(resources, it) }
    }

    /**
     * The colour an icon is mostly, for tinting its mark: the biggest hue among its vivid pixels (backplate
     * included, which is where most apps keep their colour). Null if it's all greys, or too close to the
     * card colour to read on it; the mark then stays the theme's mono colour.
     */
    internal fun accent(icon: Drawable): Int? {
        val size = 32
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.constantState?.newDrawable(resources)?.apply { setBounds(0, 0, size, size) }?.draw(Canvas(bmp)) ?: return null
        val px = IntArray(size * size).also { bmp.getPixels(it, 0, size, 0, 0, size, size) }
        bmp.recycle()
        val hsv = FloatArray(3)
        val buckets = px.filter { Color.alpha(it) > 200 }
            .filter { Color.colorToHSV(it, hsv); hsv[1] > 0.35f && hsv[2] > 0.3f }
            .groupBy { Color.colorToHSV(it, hsv); (hsv[0] / 30).toInt() } // 12 hue buckets
        val top = buckets.values.maxByOrNull { it.size }?.takeIf { it.size > px.size / 20 } ?: return null
        return Color.rgb(top.sumOf { Color.red(it) } / top.size, top.sumOf { Color.green(it) } / top.size, top.sumOf { Color.blue(it) } / top.size)
    }

    /** accent() per app, kept like GLYPHS; the contrast check is per theme, so it runs on every use. */
    internal fun cachedAccent(a: App): Int? {
        val c = if (a.pkg in ACCENTS) ACCENTS[a.pkg] else accent(a.icon).also { ACCENTS[a.pkg] = it }
        val lum = c?.let { Color.luminance(it) } ?: return null
        return c.takeIf { if (dark) lum > 0.08f else lum < 0.6f }
    }

    /** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
    internal fun customIcon(pkg: String): Drawable? =
        listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
            try {
                assets.open(path).use { Drawable.createFromStream(it, null) }
            } catch (e: IOException) {
                null
            }
        }

    // ---------- holding OK, and editing the apps row ----------

    /**
     * Hold detection lives here, not in View long-click: this TV's remote doesn't reliably trigger the
     * framework's long-press timer. We treat the first auto-repeat of OK as "held"; the long-click
     * listener in style() is a fallback for remotes that send no repeats. [holdFired] stops both firing.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val before = currentFocus
        val used = routeKey(e)
        // Android plays the move sound only for keys nobody took. Our rows and homeNav take them, so play it
        // here when focus moved. A key nobody took gets Android's own sound, so never both.
        val after = currentFocus
        if (used && e.action == KeyEvent.ACTION_DOWN && after != null && after !== before) navSound(after, e)
        return used
    }

    /** The system's focus-move sound for a D-pad key; while held, its repeat sounds (Android 12+). */
    private fun navSound(v: View, e: KeyEvent) {
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return
        }
        v.playSoundEffect(
            if (Build.VERSION.SDK_INT >= 31) SoundEffectConstants.getConstantForFocusDirection(dir, e.repeatCount > 0)
            else SoundEffectConstants.getContantForFocusDirection(dir)
        )
    }

    private fun routeKey(e: KeyEvent): Boolean {
        if (!ready) return super.dispatchKeyEvent(e)
        photoPreview?.let { preview ->
            val released = e.action == KeyEvent.ACTION_UP && !e.isCanceled
            return when (e.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (released && preview.shown) finishPhotoPreview(true)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (released && preview.choices.size > 1) {
                        val step = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        preview.index = (preview.index + step + preview.choices.size) % preview.choices.size
                        showPhotoPreview(preview)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> super.dispatchKeyEvent(e)
            }
        }
        if (wallPreview != null) { // a picked wallpaper shown whole: any button brings the panel back
            if (e.action == KeyEvent.ACTION_UP) endWallPreview()
            return true
        }
        val panel = cvteSourcePanel
        if (panel != null && !editing && !topEditing &&
            settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE &&
            Remap.cvteSourceKey(e.keyCode, e.scanCode, Remap.all(this))) {
            if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) openCvteSources(panel)
            return true
        }
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (topEditing && topEditKey(e, confirm)) return true
        if (confirm) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) holdFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!holdFired) currentFocus?.let { fireHold(it) }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
        // The settings panel is a vertical list: Left/Right step a row's value (its tag, see settingsRow) and
        // never leak focus to the home screen behind it. The PIN boxes and the city search box use them themselves.
        if (settingsScrim.visibility == View.VISIBLE && currentFocus !== pinBox && currentFocus !is EditText &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            @Suppress("UNCHECKED_CAST")
            if (e.action == KeyEvent.ACTION_DOWN) (currentFocus?.tag as? (Int) -> Unit)?.let {
                it(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                currentFocus?.let { v -> navSound(v, e) } // a value step sounds like a move
            }
            return true
        }
        // By title, the title is a pill you select: Left and Right (or OK) show another source.
        if (currentFocus === watchTitle && (confirm || e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) cycleSource(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            return true
        }
        if (editing && editKey(e, confirm)) return true
        // Per channel: Down on a card shows the next row, Up the one before (Up on the first goes to the apps).
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && cardsMode() == ShowRows.PER_CHANNEL && settingsScrim.visibility != View.VISIBLE &&
            allApps.visibility != View.VISIBLE && currentFocus?.getTag(R.id.watchRow) is Prog &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP) &&
            pageSource(if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
        ) return true
        if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
            currentFocus?.let { homeNav(it, e.keyCode) }?.let { it.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    /**
     * The home screen is three rows: top bar, apps, Continue Watching. Left/Right stay inside a row.
     * Right from the last app returns to the first; other row ends stop focus. Up/Down move between
     * rows and land where you last were, not on whatever happens to sit nearest on screen.
     * Returns the view to focus (itself = stay), or null to let Android handle the key.
     */
    private fun homeNav(v: View, key: Int): View? {
        fun alive(x: View?) = x?.takeIf { it.isAttachedToWindow }
        val apps = alive(lastApp)?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
        val cards = if (watchScroll.visibility != View.VISIBLE) null
            else alive(lastCard) ?: (watchRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
        val hero = if (billboard.visibility == View.VISIBLE) bbButton else if (heroScroll.visibility != View.VISIBLE) null else alive(lastHero) ?: heroRow.getChildAt(0)
        val title = watchTitle.takeIf { it.isFocusable && it.visibility == View.VISIBLE }
        val moreFirst = if (moreApps.visibility != View.VISIBLE) null else alive(lastMore) ?: (moreApps.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        val moreLast = if (moreApps.visibility != View.VISIBLE) null else alive(lastMore) ?: (moreApps.getChildAt(moreApps.childCount - 1) as? ViewGroup)?.getChildAt(0)
        return when {
            v.parent === wifi.parent -> when (key) { // top bar
                KeyEvent.KEYCODE_DPAD_DOWN -> hero ?: apps ?: cards ?: v // the apps row can be empty (all hidden)
                KeyEvent.KEYCODE_DPAD_UP -> v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (v.id == shownTop().first()) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (v.id == shownTop().last()) v else null
                else -> null
            }
            v === bbButton -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> top
                KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: title ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_LEFT -> { bbStep(-1); v }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { bbStep(1); v }
                else -> null
            }
            v.getTag(R.id.heroRow) is Tmdb.Hero -> {
                val i = heroRow.indexOfChild(v)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> top
                    KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: title ?: cards ?: v
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) v else null
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == heroRow.childCount - 1) v else null
                    else -> null
                }
            }
            v.parent?.parent === moreApps -> {
                val row = v.parent as ViewGroup
                val r = moreApps.indexOfChild(row)
                val c = row.indexOfChild(v)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (r == 0) apps ?: hero ?: top else (moreApps.getChildAt(r - 1) as ViewGroup).getChildAt(c)
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (r == moreApps.childCount - 1) title ?: cards ?: v
                        else (moreApps.getChildAt(r + 1) as ViewGroup).let { it.getChildAt(minOf(c, it.childCount - 1)) }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (c == 0) v else null
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (c == row.childCount - 1) v else null
                    else -> null
                }
            }
            v.parent === appRow -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> hero ?: top
                KeyEvent.KEYCODE_DPAD_DOWN -> moreFirst ?: title ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (appRow.indexOfChild(v) == 0) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (appRow.indexOfChild(v) == appRow.childCount - 1) appRow.getChildAt(0) else null
                else -> null
            }
            v === watchTitle -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> moreLast ?: apps ?: top
                KeyEvent.KEYCODE_DPAD_DOWN -> cards ?: v
                else -> null
            }
            v.getTag(R.id.watchRow) is Prog -> {
                val i = watchRow.indexOfChild(v.parent as View)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> title ?: moreLast ?: apps ?: top
                    KeyEvent.KEYCODE_DPAD_DOWN -> v
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) v else null
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == watchRow.childCount - 1) v else null
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
    internal fun homeFocusable(on: Boolean) {
        findViewById<ViewGroup>(R.id.content).descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun fireHold(v: View) {
        holdFired = true
        v.cancelLongPress()
        v.isPressed = false
        onHold(v)
    }

    /** What holding OK does depends on what's focused. */
    private fun onHold(v: View) {
        val tag = v.tag as? String
        when {
            recentAppsOpen -> if (tag != null && v.parent?.parent === recentWindowRow) recentMenu(v, tag)
            allApps.visibility == View.VISIBLE && tag != null -> unlock { // grid: pin to the front of the row (unhiding it)
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() - tag).putStringSet("home_hidden", homeHiddenSet() - tag).apply()
                saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
                closeAllApps()
                appRow.getChildAt(0)?.requestFocus() // show the app just pinned
            }
            v.parent === wifi.parent -> topMenu(v)
            v.parent === appRow -> appMenu(v)
            v.parent?.parent === moreApps -> moreMenu(v)
            v.getTag(R.id.watchRow) is Prog -> watchMenu(v, v.getTag(R.id.watchRow) as Prog)
        }
    }

    internal fun homeHiddenSet() = prefs.getStringSet("home_hidden", emptySet())!!

    internal fun reloadHero() {
        heroList = emptyList()
        heroAt = 0
        heroDetails.clear()
        refreshHero()
    }

    internal fun settingsTmdbChanged() {
        heroDetails.clear() // the services shown depend on the region and on yours
    }

    /** Hold OK on an app in the grid: make it a favourite, pin it to one of the shelf's first five spots, or hide it from the home. */
    internal fun appLabel(pkg: String, v: View) = apps.firstOrNull { it.pkg == pkg }?.label ?: v.contentDescription?.toString().orEmpty()

    /** Apps the user installed, and system apps that have an update on top. Never Nooko Launcher or an HDMI tile. */
    internal fun canUninstall(pkg: String): Boolean {
        if (pkg == packageName || pkg.startsWith(HDMI)) return false
        return try {
            val flags = packageManager.getApplicationInfo(pkg, 0).flags
            flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        } catch (_: Exception) { false }
    }

    internal fun appPictures(pkg: String, name: String) {
        if (settingsScrim.visibility != View.VISIBLE) showPanel()
        showPage(getString(R.string.change_app_picture), listOf(
            page(getString(R.string.app_picture_icon), getString(R.string.app_picture_icon_hint)) { unlock { uploadAppPicture(pkg, name, false) } },
            page(getString(R.string.banner), getString(R.string.app_picture_banner_hint)) { unlock { uploadAppPicture(pkg, name, true) } },
            page(getString(R.string.reset_app_picture)) {
                unlock {
                    AppPictures.reset(this, pkg)
                    renderApps()
                    appPictures(pkg, name)
                }
            },
        ), crumb = name) {
            closeLauncherSettings()
            (0 until appRow.childCount).map { appRow.getChildAt(it) }.firstOrNull { it.tag == pkg }?.requestFocus()
        }
    }

    private fun uploadAppPicture(pkg: String, name: String, banner: Boolean) {
        val ip = lanAddress() ?: return toast(getString(R.string.connect_network), long = true)
        if (settingsScrim.visibility != View.VISIBLE) showPanel()
        val active = AtomicBoolean(true)
        val status = label(getString(R.string.waiting_app_picture), 14f).apply { gravity = Gravity.CENTER; maxLines = 2 }
        val server = try {
            UploadServer((1000..9999).random().toString(), File.createTempFile("app-picture-", ".tmp", cacheDir), imagesOnly = true, t = phoneText()) { file, _ ->
                if (!active.get()) return@UploadServer null
                val bitmap = AppPictures.fit(file, banner) ?: return@UploadServer null
                val saved = try {
                    AppPictures.save(AppPictures.file(this, pkg, banner), bitmap) { active.get() }
                } finally { bitmap.recycle() }
                if (!saved) return@UploadServer null
                ui.post {
                    if (!active.get() || isDestroyed) return@post
                    renderApps()
                    status.text = getString(R.string.app_picture_added)
                }
                name
            }
        } catch (_: IOException) { return toast(getString(R.string.upload_failed), long = true) }
        upload = server
        val url = "http://$ip:${server.port}/${server.code}"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(url, dp(200))) })
            addView(label(url, 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(label(getString(R.string.scan_hint), 12f).apply { gravity = Gravity.CENTER; maxLines = 3 })
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
        }
        showPage(getString(if (banner) R.string.banner else R.string.app_picture_icon),
            listOf(page(getString(R.string.done)) { appPictures(pkg, name) }), top = box, crumb = name) { appPictures(pkg, name) }
        pageExit = {
            active.set(false)
            server.stop()
            if (upload === server) upload = null
        }
    }

    internal fun uninstall(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.cant_uninstall), Toast.LENGTH_SHORT).show()
        }
    }

    /** Hold OK on a Continue Watching card: hide the show, or open its app rather than the episode. */
    private fun watchMenu(v: View, p: Prog) {
        holdMenu(v, listOfNotNull(
            if (locked()) null else getString(R.string.remove_from_cw) to { hideWatch(p) },
            if (!locked()) null else getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); watchMenu(v, p) } },
            p.pkg?.let { pkg ->
                getString(R.string.open_app) to {
                    (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))
                        ?.let { startActivity(it) }
                    Unit
                }
            },
        ))
    }

    internal var activeMenu: PopupWindow? = null

    /** A small Nooko Launcher-styled menu just above [anchor] (under it when [below]); Back closes it. [rows] builds its rows. */
    /** The card under a menu or the top bar's edit panel: glass, or the theme's solid card. */
    private fun menuSurface(): LinearLayout = if (glassActive()) glassSurface(dp(14).toFloat()) else LinearLayout(this).apply {
        background = if (dark) rimCard(dp(12), false, solid = true) else GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(0xFFF7F8FA.toInt())
        }
        elevation = if (dark) 0f else dp(8).toFloat()
    }

    private fun popupMenu(anchor: View, below: Boolean = false, title: String? = null, rows: (dismiss: () -> Unit) -> List<View>) {
        val list = menuSurface()
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(6), dp(6), dp(6), dp(6))
        // A title that names what the menu is about: not a row, so focus skips it.
        if (title != null) list.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(valueInk())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val menu = PopupWindow(list, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        activeMenu = menu
        menu.setOnDismissListener { if (activeMenu === menu) activeMenu = null }
        rows { menu.dismiss() }.forEachIndexed { i, row ->
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(2)
            })
            row.minimumHeight = dp(40)
        }
        // As wide as the longest item, never narrower than the tile or card it opens from.
        // A very long translation wraps onto two lines instead of stretching across the screen.
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        menu.width = list.measuredWidth.coerceIn(anchor.width, maxOf(anchor.width, dp(360)))
        list.measure(View.MeasureSpec.makeMeasureSpec(menu.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.elevation = if (dark) 0f else dp(8).toFloat()
        // Over the anchor's left edge, but kept inside the row's 48 dp side margin near the right of the screen.
        val x = minOf(at[0], resources.displayMetrics.widthPixels - dp(48) - menu.width)
        val y = if (below) at[1] + anchor.height + dp(8) else maxOf(dp(8), at[1] - list.measuredHeight - dp(8))
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        list.getChildAt(if (title != null) 1 else 0).requestFocus()
    }

    /** A menu of [items]. [holds] (by position, null for none) is what holding OK on an item does. */
    internal fun holdMenu(anchor: View, items: List<Pair<String, () -> Unit>>, below: Boolean = false, holds: List<(() -> Unit)?> = emptyList(), title: String? = null) =
        popupMenu(anchor, below, title) { dismiss ->
            items.mapIndexed { i, (name, action) ->
                settingsRow(name, "") { dismiss(); action() }.also { row -> holds.getOrNull(i)?.let { onHoldOk(row) { dismiss(); it() } } }
            }
        }

    /**
     * Holding OK on a row in a menu: the first repeat of OK, or the long click on remotes that send no repeats.
     * [holdFired] then keeps the OK that is let go, back on the home screen, from clicking what is under the menu.
     */
    private fun onHoldOk(row: View, run: () -> Unit) {
        var fired = false
        val hold = { if (!fired) { fired = true; holdFired = true; run() } }
        row.setOnKeyListener { _, code, e ->
            val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (ok && e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) { hold(); true } else false
        }
        row.setOnLongClickListener { hold(); true }
    }

    // ---------- the top bar ----------

    /** The bar's icons in their default order, with the key each is saved under. */
    private val TOP_KEYS = linkedMapOf(R.id.wifi to "wifi", R.id.sourcesButton to "sources", R.id.allAppsButton to "apps",
        R.id.systemSettings to "system", R.id.launcherSettings to "atv", R.id.heartButton to "heart", R.id.powerButton to "power",
        R.id.recentsButton to "recents", R.id.screensaverButton to "screensaver")

    /** The icons the bar shows, in order: the saved list, or all of them. Nooko Launcher settings is always on it. */
    private fun topOrder(): List<Int> {
        val all = TOP_KEYS.values.toList()
        // Power, Recent apps and Screensaver are not on the bar until someone adds them.
        return keys(TopBar.parse(prefs.getString("top_bar", null), all, all - setOf("power", "recents", "screensaver"), "atv"))
    }

    private fun hasSources() = sources.isNotEmpty() || cvteSourcePanel != null

    /** What is really on screen: Sources stays out while there is nothing to open, and no more than [TopBar.MAX] icons. */
    internal fun shownTop(order: List<Int> = topOrder()) =
        keys(TopBar.cap(keyList(order.filter { it != R.id.sourcesButton || hasSources() }), "atv"))

    /** Puts the icons in [order], hides the others, and gives the first one on screen no left margin. */
    internal fun applyTopBar(order: List<Int> = topOrder()) {
        val bar = wifi.parent as LinearLayout
        // Found before they leave the bar: findViewById cannot see a view that has been removed.
        val views = TOP_KEYS.keys.associateWith { findViewById<View>(it) }
        val start = views.values.minOf { bar.indexOfChild(it) }
        val focused = currentFocus?.takeIf { it.parent === bar }
        views.values.forEach { bar.removeView(it) }
        val shown = shownTop(order)
        (order + (TOP_KEYS.keys - order.toSet())).forEachIndexed { i, id ->
            val v = views.getValue(id)
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.marginStart = if (id == shown.firstOrNull()) 0 else dp(8)
            v.visibility = if (id in shown) View.VISIBLE else View.GONE
            bar.addView(v, start + i, lp)
        }
        // Taking the focused icon out passed focus on, down to the empty app row's scroller on a cold start, and
        // then the first fill of the row did not take it. Give it back to the bar.
        focused?.let { (if (it.visibility == View.VISIBLE) it else findViewById(shown.first())).requestFocus() }
    }

    private fun saveTopOrder(order: List<Int>) {
        prefs.edit().putString("top_bar", order.joinToString(",") { TOP_KEYS.getValue(it) }).apply()
    }

    private fun keyList(ids: List<Int>) = ids.map { TOP_KEYS.getValue(it) }

    internal fun keys(list: List<String>) = list.map { key -> TOP_KEYS.entries.first { it.value == key }.key }

    /**
     * The system's own power dialog (power off, restart; Cancel leaves everything as it was). Only the Home button service can
     * ask for it, so without the service the icon offers to turn that on.
     */
    private fun powerDialog() {
        val service = HomeOverride.running
            ?: return holdMenu(findViewById(R.id.powerButton), listOf(getString(R.string.power_needs_home) to { turnOnHomeButton() }), below = true)
        Remap.powerMenu(service)
    }

    /** A menu row with [icon] before its name. The row is a column (name line, then choices), so the icon goes in the name line. */
    private fun rowIcon(row: View, icon: Drawable?) = row.also {
        ((it as ViewGroup).getChildAt(0) as LinearLayout).addView(ImageView(this).apply {
            setImageDrawable(icon)
            setColorFilter(if (dark) DARK_TEXT_2 else fg)
        }, 0, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
    }

    // ---------- editing the top bar, right on the bar ----------
    // Hold OK on any icon: the bar goes into edit mode and a panel under it shows the icons that are not on it.
    // Everything is a key press on the bar itself: OK lifts an icon, Left and Right move it, OK places it, Down
    // takes it off. OK on an icon in the panel puts it on the bar; on a full bar it takes the place of one icon.
    // Every change is saved when it is placed, so Back only puts back a lifted icon, then ends editing.

    private var topTray: LinearLayout? = null // the panel under the bar; null when the bar is not being edited
    private var trayIcons: LinearLayout? = null
    private var trayCount: TextView? = null
    private var trayName: TextView? = null
    private var trayHint: TextView? = null
    private var movingTop: View? = null // the lifted icon: Left and Right move it, OK places it
    private var topLive: List<Int> = emptyList() // the order on screen while editing
    private var topBefore: List<Int> = emptyList() // the order when the icon was lifted, for Back
    private var swapIn: Int? = null // a full bar: the icon being added
    private var swapOut: Int? = null // and the one it takes the place of

    internal val topEditing get() = topTray != null

    /** Hold OK on a top-bar icon: edit the bar right there. With a PIN set, Nooko Launcher asks for it first. */
    private fun topMenu(v: View) {
        if (topEditing) return
        if (locked()) return unlock {
            if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
            v.requestFocus()
            topMenu(v)
        }
        startTopEdit(v)
    }

    private fun startTopEdit(v: View) {
        val shown = shownTop()
        // Icons past the limit leave the saved list, so the panel offers them again. Sources with no input keeps its place.
        topLive = topOrder().filter { it in shown || it == R.id.sourcesButton && !hasSources() }
        saveTopOrder(topLive)
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val panel = menuSurface()
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(20), dp(14), dp(20), dp(14))
        panel.minimumWidth = dp(360)
        val small = { text: String -> label(text, 13f).apply { setTextColor(valueInk()); panelShadow() } }
        trayCount = small("").also { panel.addView(it) }
        trayName = label("", 17f).apply { setTextColor(focusInk()); panelShadow() }
            .also { panel.addView(it, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(2) }) }
        panel.addView(small(getString(R.string.top_more)), LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(14) })
        trayIcons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44) }
            .also { panel.addView(it, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(6) }) }
        trayHint = small("").also { panel.addView(it, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(12) }) }
        val bar = wifi.parent as View
        val root = findViewById<FrameLayout>(R.id.root)
        // The header also holds the clock. Use the icon's bottom edge for the panel.
        val at = Rect(0, 0, bar.width, v.bottom)
        root.offsetDescendantRectToMyCoords(bar, at)
        panel.elevation = dp(16).toFloat()
        root.addView(panel, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
            topMargin = at.bottom + dp(8)
            marginEnd = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) at.left else root.width - at.right
        })
        topTray = panel
        fillTray()
        v.requestFocus()
        topSays(v.id)
    }

    /** Ends editing: a lifted icon goes back first. Called by Back, and when Home or an app takes over. */
    private fun endTopEdit() {
        val panel = topTray ?: return
        if (swapIn != null) undoSwap()
        if (movingTop != null) undoTop()
        val back = barIcon()
        findViewById<FrameLayout>(R.id.root).removeView(panel)
        topTray = null
        trayIcons = null
        back.requestFocus()
    }

    /** The bar icon to come back to from the panel: the last one focused on the bar, or Nooko Launcher settings. */
    private fun barIcon(): View = lastTop?.takeIf { it.parent === wifi.parent && it.visibility == View.VISIBLE }
        ?: findViewById(R.id.launcherSettings)

    /** The icons not on the bar. Sources only on a TV with an input: elsewhere it would not show once added. */
    private fun trayList() = TOP_KEYS.keys.filter { it !in topLive && (it != R.id.sourcesButton || hasSources()) }

    private fun fillTray(focus: Int? = null) {
        val row = trayIcons ?: return
        row.removeAllViews()
        for (id in trayList()) {
            val src = findViewById<ImageView>(id)
            row.addView(ImageView(this).apply {
                tag = id
                setImageDrawable(src.drawable.constantState?.newDrawable(resources)?.mutate() ?: src.drawable)
                contentDescription = src.contentDescription
                setColorFilter(restInk())
                setPadding(dp(9), dp(9), dp(9), dp(9))
                isFocusable = true
                setOnFocusChangeListener { v, focused ->
                    v.background = if (focused) plate(20) else null
                    setColorFilter(if (focused) focusInk() else restInk())
                    if (focused) topSays(id, inTray = true)
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { if (row.childCount > 0) marginStart = dp(8) })
        }
        if (row.childCount == 0) row.addView(label(getString(R.string.top_all_on), 15f).apply { setTextColor(restInk()); panelShadow() })
        focus?.let { id -> trayView(id)?.requestFocus() }
    }

    private fun trayView(id: Int): View? = trayIcons?.let { r -> (0 until r.childCount).map(r::getChildAt).firstOrNull { it.tag == id } }

    /** The panel's lines for the icon in focus: how full the bar is, the icon's name, and what each key does now. */
    private fun topSays(id: Int, inTray: Boolean = false) {
        trayCount?.text = getString(R.string.top_count, shownTop(topLive).size, TopBar.MAX)
        val name = findViewById<View>(id).contentDescription
        val out = swapOut
        trayName?.text = when {
            out != null -> getString(R.string.top_replaces, name, findViewById<View>(out).contentDescription)
            id == R.id.launcherSettings -> getString(R.string.top_always, name)
            else -> name
        }
        trayHint?.text = getString(when {
            out != null -> R.string.top_hint_swap
            movingTop != null -> if (id == R.id.launcherSettings) R.string.top_hint_lifted_atv else R.string.top_hint_lifted
            inTray -> if (shownTop(topLive).size >= TopBar.MAX) R.string.top_hint_tray_full else R.string.top_hint_tray
            else -> R.string.top_hint_bar
        })
    }

    internal fun lift(v: View, up: Boolean) {
        v.translationY = if (up) -dp(6).toFloat() else 0f
    }

    private fun liftTop(v: View) {
        topBefore = topLive
        movingTop = v
        lift(v, true)
        topSays(v.id)
    }

    private fun slideTop(dir: Int) {
        val v = movingTop ?: return
        val order = keys(TopBar.slide(keyList(topLive), keyList(shownTop(topLive)), TOP_KEYS.getValue(v.id), dir))
        if (order == topLive) return
        topLive = order
        applyTopBar(order) // the view leaves the bar and comes back: take focus again
        v.requestFocus()
    }

    private fun placeTop() {
        val v = movingTop ?: return
        movingTop = null
        lift(v, false)
        saveTopOrder(topLive)
        topSays(v.id)
    }

    /** Back on a lifted icon: the bar as it was. An icon that was just added goes back to the panel. */
    private fun undoTop() {
        val v = movingTop ?: return
        movingTop = null
        lift(v, false)
        topLive = topBefore
        applyTopBar(topLive)
        if (v.id in topLive) { v.requestFocus(); topSays(v.id) } else fillTray(focus = v.id)
    }

    /** Down on a lifted icon: off the bar, into the panel. Nooko Launcher settings stays. */
    private fun removeTop() {
        val v = movingTop ?: return
        if (v.id == R.id.launcherSettings) return
        movingTop = null
        lift(v, false)
        topLive = topLive - v.id
        applyTopBar(topLive)
        saveTopOrder(topLive)
        fillTray(focus = v.id)
    }

    /** OK on an icon in the panel: onto the bar after the icon last focused there, lifted, so Left and Right place it. */
    private fun addTop(id: Int) {
        topBefore = topLive
        val after = barIcon().id
        if (shownTop(topLive).size >= TopBar.MAX) {
            swapIn = id
            val targets = swapTargets()
            return showSwap(after.takeIf { it in targets } ?: targets.last())
        }
        topLive = keys(TopBar.addAfter(keyList(topLive), TOP_KEYS.getValue(id), TOP_KEYS.getValue(after)))
        applyTopBar(topLive)
        val v = findViewById<View>(id)
        v.requestFocus() // before the panel refills: its focused icon leaves it
        movingTop = v
        lift(v, true)
        fillTray()
        topSays(id)
    }

    /** On a full bar the added icon can take the place of any icon but Nooko Launcher settings. */
    private fun swapTargets() = shownTop(topBefore) - R.id.launcherSettings

    private fun showSwap(out: Int) {
        val id = swapIn ?: return
        swapOut = out
        topLive = keys(TopBar.swap(keyList(topBefore), TOP_KEYS.getValue(out), TOP_KEYS.getValue(id)))
        applyTopBar(topLive)
        val v = findViewById<View>(id)
        v.requestFocus()
        lift(v, true)
        topSays(id)
    }

    private fun stepSwap(dir: Int) {
        val targets = swapTargets()
        targets.getOrNull(targets.indexOf(swapOut) + dir)?.let { showSwap(it) }
    }

    private fun placeSwap() {
        val id = swapIn ?: return
        swapIn = null
        swapOut = null
        val v = findViewById<View>(id)
        lift(v, false)
        saveTopOrder(topLive)
        fillTray()
        v.requestFocus()
        topSays(id)
    }

    private fun undoSwap() {
        val id = swapIn ?: return
        swapIn = null
        swapOut = null
        lift(findViewById(id), false)
        topLive = topBefore
        applyTopBar(topLive)
        fillTray(focus = id)
    }

    /** Left and Right on the bar or in the panel: the next icon, never out of either. */
    private fun stepFocus(dir: Int, inTray: Boolean) {
        val f = currentFocus ?: return
        if (inTray) {
            val row = trayIcons ?: return
            row.getChildAt(row.indexOfChild(f) + dir)?.takeIf { it.isFocusable }?.requestFocus()
            return
        }
        val shown = shownTop(topLive)
        shown.getOrNull(shown.indexOf(f.id) + dir)?.let { findViewById<View>(it).requestFocus(); topSays(it) }
    }

    /** Every key while the bar is edited. Keys it has no use for (Home, volume, media) go on as usual. */
    private fun topEditKey(e: KeyEvent, confirm: Boolean): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val f = currentFocus
        val inTray = f != null && f.parent === trayIcons
        when {
            confirm -> {
                if (down) {
                    if (e.repeatCount == 0) holdFired = false
                    return true
                }
                if (holdFired) { holdFired = false; return true } // the OK held to start editing, let go
                when {
                    swapIn != null -> placeSwap()
                    movingTop != null -> placeTop()
                    inTray -> addTop(f!!.tag as Int)
                    f != null && f.parent === wifi.parent -> liftTop(f)
                }
            }
            e.keyCode == KeyEvent.KEYCODE_BACK -> if (!down) when {
                swapIn != null -> undoSwap()
                movingTop != null -> undoTop()
                else -> endTopEdit()
            }
            e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val dir = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                when {
                    swapIn != null -> stepSwap(dir)
                    movingTop != null -> slideTop(dir)
                    else -> stepFocus(dir, inTray)
                }
            }
            e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> if (down && e.repeatCount == 0) when {
                swapIn != null -> undoSwap()
                movingTop != null -> removeTop()
                !inTray -> trayIcons?.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
            }
            e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> if (down && e.repeatCount == 0 && inTray) barIcon().let { it.requestFocus(); topSays(it.id) }
            else -> return false
        }
        return true
    }

    private fun openCvteSources(panel: Intent) {
        try {
            startActivity(panel)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.tv_sources_failed))
        } catch (_: SecurityException) {
            toast(getString(R.string.tv_sources_failed))
        }
    }

    /** The native source panel where available, plus the HDMI inputs users can pin. */
    private fun showSources(anchor: View) {
        if (!hasSources()) return
        popupMenu(anchor, below = true) { dismiss ->
            val pinned = pinnedInputs()
            val native = cvteSourcePanel?.let { panel ->
                settingsRow(getString(R.string.tv_sources), "") { dismiss(); openCvteSources(panel) }
            }
            val hdmi = sources.sortedBy { it.label.lowercase() }.map { src ->
                val state = listOfNotNull(
                    if (src.offline) getString(R.string.no_signal) else null,
                    if (src.pkg in pinned) getString(R.string.in_row) else null,
                ).joinToString(" · ")
                rowIcon(settingsRow(src.label, state) { dismiss(); launch(src) }, src.icon).also { row ->
                    row.setOnLongClickListener { dismiss(); unlock { togglePin(src) }; true } // hold OK: in or out of the home row
                }
            }
            val hint = if (hdmi.isEmpty()) null else label(getString(R.string.sources_hint), 12f).apply {
                secondary(0.6f)
                setPadding(dp(14), dp(8), dp(14), dp(4))
                isSingleLine = false // short, and it wraps rather than widen the list
                maxWidth = dp(180)
            }
            listOfNotNull(native) + hdmi + listOfNotNull(hint)
        }
    }

    private fun appTileCount() = appRow.childCount

    internal var moveOnce = false // moving one app from the hold menu: OK places it and editing ends

    internal fun setGrabbed(v: View?) {
        grabbed?.alpha = 1f
        grabbed = v
        v?.alpha = 0.6f
    }

    private fun endEdit() {
        setGrabbed(null)
        editing = false
        moveOnce = false
        appsTitle.visibility = View.GONE
        val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
        currentFocus?.takeIf { it.parent === appRow }?.let { showRowLabel(it, true) } // the name was hidden while editing
    }

    /** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
    private fun editKey(e: KeyEvent, confirm: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) endEdit()
            return true
        }
        val v = currentFocus ?: return false
        val i = appRow.indexOfChild(v)
        if (confirm) {
            if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) {
                if (moveOnce) endEdit() else setGrabbed(if (grabbed === v) null else v)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (j !in 0 until appTileCount()) return true
                if (grabbed === v) {
                    // Swap layout params too, so the first tile keeps no leading margin.
                    val other = appRow.getChildAt(j)
                    val lp = v.layoutParams
                    v.layoutParams = other.layoutParams
                    other.layoutParams = lp
                    appRow.removeViewAt(i)
                    appRow.addView(v, j)
                    showNames()
                    v.requestFocus()
                    return true
                }
                return false // not holding anything: normal focus movement within the row
            }
        }
        return false
    }

    /** Apps left out of the home row; they stay in All apps. New installs show until hidden. */
    internal fun rowHiddenSet() = prefs.getStringSet("row_hidden", emptySet())!!

    internal fun pickRowApps() {
        val list = sortedApps().filter { !it.pkg.startsWith(HDMI) } // inputs are pinned from the Sources list
        toggles(getString(R.string.apps_in_row), list.map { it.label to it.pkg }, "row_hidden", getString(R.string.shown), getString(R.string.hidden)) {
            renderApps()
            renderSettings(R.string.apps_in_row)
        }
    }

    /** A page of on/off rows saved as the set of keys that are off; [done] runs on Back. */
    /** [pref] holds the keys switched from where they start: on, or off for the keys in [startsOff]. */
    internal fun toggles(title: String, items: List<Pair<String, String>>, pref: String, on: String, off: String, focus: Int = 0,
                        startsOff: Set<String> = emptySet(), done: () -> Unit) {
        val offSet = prefs.getStringSet(pref, emptySet())!!
        showPage(title, items.mapIndexed { i, (name, key) ->
            page(name, if ((key in offSet) != (key in startsOff)) off else on) {
                prefs.edit().putStringSet(pref, if (key in offSet) offSet - key else offSet + key).apply()
                toggles(title, items, pref, on, off, i, startsOff, done)
            }
        }, focus, back = done)
    }

    internal fun saveOrder(pkgs: List<String>) {
        // Arranging the apps by hand puts the pinned ones in your order too.
        prefs.edit().putString("order", pkgs.joinToString(",")).putString("sort", "manual").putString("fav_sort", "custom").apply()
    }

    internal fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).putLong("t_" + a.pkg, System.currentTimeMillis()).apply()
        if (prefs.getString("sort", "manual") == "usage" || favSortUsage()) appsDirty = true
        val from = currentFocus?.takeIf { it.tag == a.pkg } // the tile that was pressed, not one a remote key opened it from
        try {
            if (a.pkg.startsWith(HDMI)) {
                Remap.openInput(this, a.pkg.removePrefix(HDMI))
                returnTo = focusKey(from)
            } else startApp(a.launch, from)
        } catch (e: Exception) {
            (packageManager.getLeanbackLaunchIntentForPackage(a.pkg) ?: packageManager.getLaunchIntentForPackage(a.pkg))?.let { startApp(it, from) }
        }
    }

    internal fun animationsOn() = prefs.getBoolean("app_anim", true)

    private var openedFrom: WeakReference<View>? = null // the tile or card an app was opened from, until we come back

    /** Starts an app. With [from] and the animations on, the app opens out of that tile and the row settles on return. */
    private fun startApp(intent: Intent, from: View?) {
        val source = from?.takeIf { animationsOn() && it.isAttachedToWindow && it.width > 0 }
        val options = source?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        startActivity(intent, options)
        openedFrom = source?.let { WeakReference(it) }
        returnTo = focusKey(from)
    }

    /** What Home goes back to: the pressed tile's package or the pressed card's [cardKey], and null for an app that Nooko Launcher did not start. */
    private var returnTo: String? = null

    private fun cardKey(p: Prog) = "card|${p.title}|${p.pkg}|${p.intent}"

    private fun focusKey(v: View?): String? = (v?.getTag(R.id.watchRow) as? Prog)?.let { cardKey(it) } ?: v?.tag as? String

    /** The tile or card that [key] names, with the cards built up to it; null when it left the rows (uninstalled, hidden, off the list). */
    private fun homeViewFor(key: String): View? {
        (0 until appRow.childCount).map { appRow.getChildAt(it) }.firstOrNull { it.tag == key }?.let { return it }
        val at = watchList.indexOfFirst { cardKey(it) == key }
        if (at < 0) return null
        addCardsTo(at)
        return (watchRow.getChildAt(at) as? ViewGroup)?.getChildAt(0)
    }

    /**
     * Back from an app: the row of the tile it came from settles in, a little larger and dimmer at first. It settles
     * around that tile when it is on screen, and around the middle of the row's window when Home has moved the row.
     */
    private fun settleRow(tile: View) {
        // A Continue watching card sits in a column of its own: the row is the one that holds the columns.
        val row = (if (tile.getTag(R.id.watchRow) is Prog) watchRow else tile.parent) as? ViewGroup ?: return
        row.animate().cancel()
        val at = Rect(0, 0, tile.width, tile.height)
        row.offsetDescendantRectToMyCoords(tile, at) // the tile's place in the row, whatever sits between them
        var pivot = at.exactCenterX()
        val scroll = row.ancestor<HorizontalScrollView>()
        if (scroll != null && (at.left < scroll.scrollX || at.right > scroll.scrollX + scroll.width)) pivot = scroll.scrollX + scroll.width / 2f
        row.pivotX = pivot
        row.pivotY = at.exactCenterY()
        row.scaleX = 1.12f
        row.scaleY = 1.12f
        row.alpha = 0.4f
        // A moment late: the app's own window is still fading out over us for the first part.
        row.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(90).setDuration(420).setInterpolator(DECELERATE).start()
        // The row scales and fades with no layout and no focus change: the frost must redraw as it settles.
        glass.kick(600)
    }

    // ---------- all apps ----------

    internal var recentAppsOpen = false
    private val systemHomePress = HomePress()
    internal var recentsBack: Pair<WeakReference<View>, String?>? = null
    internal val recentWindows by lazy { findViewById<RecentWindowStrip>(R.id.recentWindows) }
    internal val recentWindowRow by lazy { findViewById<LinearLayout>(R.id.recentWindowRow) }

    /**
     * Recent apps' Clear all, small, at the end of the title's line. Made once: the title moves into a header row
     * with it (All apps keeps the same title, the button hidden).
     */
    internal val recentClear by lazy {
        val title = findViewById<TextView>(R.id.allAppsTitle)
        val column = title.parent as ViewGroup
        val at = column.indexOfChild(title)
        val params = title.layoutParams as LinearLayout.LayoutParams
        column.removeView(title)
        val header = FrameLayout(this)
        header.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.CENTER_VERTICAL))
        column.addView(header, at, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = params.bottomMargin })
        pill(getString(R.string.recent_clear_all), small = true) { clearRecentApps() }.also { button ->
            header.addView(button, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(48) })
            button.nextFocusLeftId = button.id
            button.nextFocusRightId = button.id
            button.nextFocusUpId = button.id
        }
    }

    /** A button in the home row's tile style (glass or rim card, rise on focus); [small] for the quiet ones. */
    internal fun pill(text: String, small: Boolean = false, onClick: () -> Unit) = LinearLayout(this).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER
        if (small) setPadding(dp(14), dp(6), dp(14), dp(6)) else setPadding(dp(18), dp(8), dp(18), dp(8))
        addView(label(text, if (small) 13f else 15f))
        style(this, dp(8), card = true)
        elevation = 0f
        setOnClickListener { onClick() }
    }

    /**
     * What a window shows under its title bar: the app's last screen, else its Continue Watching art, else its banner
     * (never stretched past its own size) or icon. Rounded inside the frosted frame, like a window on a pane.
     */
    internal fun windowContent(app: App, picture: Bitmap?, uri: String?, wide: Int): View = FrameLayout(this).apply {
        val inner = dp(8).toFloat()
        background = GradientDrawable().apply { cornerRadius = inner; setColor(0x26000000) }
        clipToOutline = true
        val image = ImageView(this@MainActivity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        if (picture == null && uri == null) {
            val banner = appBanner(app)?.takeIf { it.intrinsicWidth >= dp(120) }
            image.setImageDrawable(banner ?: app.icon)
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            val w = banner?.let { minOf(it.intrinsicWidth, wide / 2) } ?: dp(88)
            val h = banner?.let { w * it.intrinsicHeight / maxOf(1, it.intrinsicWidth) } ?: dp(88)
            addView(image, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
            return@apply
        }
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (picture != null) return@apply image.setImageBitmap(picture)
        // The window is ~4x a card's width: the card's small RGB_565 poster would be stretched soft and banded.
        val key = "window|$uri"
        image.tag = key
        windowArt.get(key)?.let { return@apply image.setImageBitmap(it) }
        posterIo.execute {
            val bmp = try { decode(uri!!, wide, Bitmap.Config.ARGB_8888) } catch (e: Exception) { null } ?: return@execute
            windowArt.put(key, bmp)
            ui.post { if (image.tag == key) image.setImageBitmap(bmp) }
        }
    }

    /** Where the strip is, in windows from the first (fractional while it moves); see [placeWindows]. */
    internal var recentAt = 0f
    internal var recentMotion: ValueAnimator? = null

    /**
     * One position drives the whole strip, so the scroll, each window's size and its pull toward the centre move
     * as one piece: [at] 2.5 is halfway between the third and fourth window.
     */
    internal fun placeWindows(at: Float, width: Int, gap: Int) {
        recentAt = at
        val rtl = recentWindows.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val count = recentWindowRow.childCount
        recentWindows.scrollTo(((if (rtl) count - 1 - at else at) * (width + gap)).toInt(), 0)
        for (i in 0 until count) {
            val d = i - at
            val far = kotlin.math.abs(d)
            val shrink = width * (1 - SIDE_WINDOW)
            // Smaller side windows leave a hole: pull each one in so every visible gap stays [gap] wide.
            val pull = shrink * (if (far < 1) far / 2 else far - 0.5f)
            recentWindowRow.getChildAt(i).apply {
                val size = 1 - (1 - SIDE_WINDOW) * minOf(far, 1f)
                scaleX = size
                scaleY = size
                translationX = if ((d > 0) != rtl) -pull else pull
            }
        }
    }

    /** Glide the strip to window [to] from wherever it is now; a new press takes over mid-way, with no jump. */
    internal fun moveWindows(to: Int, width: Int, gap: Int) {
        recentMotion?.cancel()
        val from = recentAt
        if (from == to.toFloat()) return placeWindows(from, width, gap)
        if (!animationsOn() || !ValueAnimator.areAnimatorsEnabled()) return placeWindows(to.toFloat(), width, gap)
        glass.kick(400) // the frost follows the windows while they move
        recentMotion = ValueAnimator.ofFloat(from, to.toFloat()).apply {
            duration = 300
            interpolator = EASE
            addUpdateListener { placeWindows(it.animatedValue as Float, width, gap) }
            start()
        }
    }

    /** The window that should be selected when the strip is rebuilt (see buildRecentWindows). */
    internal var recentsFocus: String? = null

    /** Remove: the window fades and shrinks away, then the strip closes the gap from the next window. */
    internal fun removeWindow(card: View, pkg: String, index: Int) {
        forgetRecent(pkg)
        val done = { openAllApps(recent = true, at = index) }
        if (!animationsOn() || !ValueAnimator.areAnimatorsEnabled()) return done()
        card.animate().alpha(0f).scaleX(SIDE_WINDOW).scaleY(SIDE_WINDOW).setDuration(180).setInterpolator(EASE)
            .withEndAction { if (recentAppsOpen) done() }.start()
    }

    /**
     * Hold OK on a recent window: open it, or take it off the list. No "Close app": Android 14 and newer don't let one
     * app stop another, and on a Google TV with Android 12 killBackgroundProcesses was refused ("Invalid packageName").
     */
    private fun recentMenu(v: View, pkg: String) {
        val app = sortedApps().firstOrNull { it.pkg == pkg } ?: return
        val at = (0 until recentWindowRow.childCount).indexOfFirst { (recentWindowRow.getChildAt(it) as ViewGroup).getChildAt(0) === v }
        holdMenu(v, listOf(
            getString(R.string.recent_open) to { launch(app) },
            getString(R.string.recent_remove) to { forgetRecent(pkg); openAllApps(recent = true, at = at) },
        ))
    }

    /** Off the list, with its saved picture, until it is opened again (see recentApps). */
    private fun forgetRecent(pkg: String) {
        prefs.edit().putLong("r_$pkg", System.currentTimeMillis()).apply()
        RecentPreviews.remove(this, pkg)
    }

    private fun clearRecentApps() {
        for (i in 0 until recentWindowRow.childCount) {
            ((recentWindowRow.getChildAt(i) as ViewGroup).getChildAt(0).tag as? String)?.let { forgetRecent(it) }
        }
        RecentPreviews.clear(this)
        openAllApps(recent = true)
    }

    private fun closeAllApps() {
        allApps.visibility = View.GONE
        val back = recentsBack.takeIf { recentAppsOpen }
        recentsBack = null
        recentAppsOpen = false
        recentWindowRow.removeAllViews()
        findViewById<View>(R.id.content).visibility = View.VISIBLE
        homeFocusable(true)
        renderApps()
        // The row is rebuilt, so a tile is found again by its tag; the top icons stay the same views.
        val again = back?.second?.let { homeViewFor(it) } ?: back?.first?.get()?.takeIf { it.isAttachedToWindow }
        (again ?: findViewById(R.id.allAppsButton)).requestFocus() // back where it was opened from
    }

    // ---------- screensaver ----------
    // Gallery on: remember whatever screensaver was set and switch to ours.
    // Gallery off: put that one back. Needs the adb-granted WRITE_SECURE_SETTINGS, like the Home service.

    internal fun gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

    private fun currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

    internal fun galleryOn() = currentScreensaver() == gallery()

    internal fun screensaverName(component: String = currentScreensaver()): String {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return getString(R.string.off)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.system_default)
        }
    }

    /** The folder the Gallery screensaver shows instead of paintings, or null for paintings. */
    internal fun photoScreensaver() = prefs.getString("ss_folder", null)?.takeIf { it != TMDB_SCREENSAVER }

    internal fun tmdbScreensaverOn() = galleryOn() && prefs.getString("ss_folder", null) == TMDB_SCREENSAVER

    internal fun pickScreensaver(focus: Int = 0) {
        val photos = galleryOn() && photoScreensaver() != null
        val other = if (galleryOn()) prefs.getString("prev_screensaver", "").orEmpty() else currentScreensaver()
        showPage(getString(R.string.screensaver), listOf(
            page(getString(R.string.tmdb_screensaver), if (tmdbScreensaverOn()) CHOSEN else if (Tmdb.key(this) == null) getString(R.string.tmdb_needs_key) else "") {
                if (Tmdb.key(this) == null) toast(getString(R.string.tmdb_needs_key), long = true) else useGallery(TMDB_SCREENSAVER)
                pickScreensaver(0)
            },
            page(getString(R.string.your_photos), if (photos) "${wallpaperName(photoScreensaver()!!)} $CHOSEN" else "") {
                pickFolder({ pickScreensaver(1) }) { choice ->
                    useGallery(choice)
                    pickScreensaver(1)
                }
            },
            page(screensaverName(other), if (galleryOn()) "" else CHOSEN) {
                if (galleryOn()) stopGallery()
                pickScreensaver(2)
            },
        ), focus) { renderSettings(R.string.screensaver) }
    }


    /** Gallery on, with paintings ([folder] null, downloaded first if needed) or a folder of photos. */
    private fun useGallery(folder: String?) {
        prefs.edit().putString("ss_folder", folder).apply()
        if (galleryOn()) return
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            // Without the adb permission Nooko Launcher can't switch it itself: ask for it, then switch it.
            return askSecureGrant { useGallery(folder) }
        }
        prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
        Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
    }

    /** Back to whatever screensaver was set before Gallery. */
    private fun stopGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return openScreensaverSettings()
        Settings.Secure.putString(contentResolver, "screensaver_components", prefs.getString("prev_screensaver", "") ?: "")
    }

    private fun readDreamSetting(key: String): String? = if (key == Settings.System.SCREEN_OFF_TIMEOUT)
        Settings.System.getString(contentResolver, key) else Settings.Secure.getString(contentResolver, key)

    internal fun screensaverDelayName(): String {
        if (readDreamSetting("screensaver_enabled") == "0") return getString(R.string.never)
        val key = if (fireTv() && readDreamSetting("contextual_screen_off_timeout") != null)
            "contextual_screen_off_timeout" else Settings.System.SCREEN_OFF_TIMEOUT
        val ms = readDreamSetting(key)?.toLongOrNull() ?: return ""
        if (ms <= 0 || ms % 60_000 != 0L) return ""
        val minutes = (ms / 60_000).toInt()
        return resources.getQuantityString(R.plurals.screensaver_minutes, minutes, minutes)
    }

    internal fun pickScreensaverDelay() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            val hint = label(getString(R.string.screensaver_delay_permission) + "\n\nadb shell pm grant $packageName $WRITE_SECURE", 14f)
                .apply { maxLines = 8; ellipsize = null }
            return showPage(getString(R.string.screensaver_timing),
                listOf(page(getString(R.string.adb_allow_now), "") { askSecureGrant { pickScreensaverDelay() } },
                    page(getString(R.string.act_settings), "") { openScreensaverSettings() }), top = hint) {
                renderSettings(R.string.screensaver_timing)
            }
        }
        val choices = SCREENSAVER_DELAYS
        val current = screensaverDelayName()
        showPage(getString(R.string.screensaver_timing), choices.map { minutes ->
            val name = if (minutes == 0) getString(R.string.never)
                else resources.getQuantityString(R.plurals.screensaver_minutes, minutes, minutes)
            page(name, if (name == current) CHOSEN else "") {
                try {
                    saveScreensaverDelay(minutes, fireTv(), { readDreamSetting(it) }) { key, value ->
                        if (key == Settings.System.SCREEN_OFF_TIMEOUT) Settings.System.putString(contentResolver, key, value)
                        else Settings.Secure.putString(contentResolver, key, value)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Nooko Launcher", "Could not save screensaver timing", e)
                    toast(getString(R.string.screensaver_delay_failed), long = true)
                }
                pickScreensaverDelay()
            }
        } + page(getString(R.string.act_settings), "") { openScreensaverSettings() },
            maxOf(0, choices.indexOfFirst { minutes ->
                current == if (minutes == 0) getString(R.string.never)
                    else resources.getQuantityString(R.plurals.screensaver_minutes, minutes, minutes)
            })) { renderSettings(R.string.screensaver_timing) }
    }

    /** Android's screensaver settings (timing, sleep): plain Android TV and Google TV name them differently. */
    private fun openScreensaverSettings() {
        returnTo(R.string.screensaver_timing)
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, "com.google.android.tv.settings.ambient")) {
            try {
                return startActivity(Intent(action))
            } catch (e: Exception) {
            }
        }
        openSettings(Settings.ACTION_SETTINGS)
    }

    @Volatile internal var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    /** Fetches [files] from [url] into [dir], all or nothing; [done] runs on the UI thread with whether they all arrived. */
    internal fun download(url: String, files: List<String>, dir: File, start: String, failed: Int, done: (Boolean) -> Unit) {
        if (downloading) return
        downloading = true
        toast(start)
        Thread {
            val part = File(dir.path + ".part").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in files) openStream(url + f).use { input ->
                    File(part, f).outputStream().use { input.copyTo(it) }
                }
                dir.deleteRecursively()
                part.renameTo(dir)
            } catch (e: Exception) {
                android.util.Log.w("Nooko Launcher", "download from $url failed ($e)")
                part.deleteRecursively()
                false
            }
            ui.post {
                if (isDestroyed) return@post
                downloading = false
                if (!ok) toast(getString(failed), long = true)
                done(ok)
            }
        }.start()
    }

    internal fun testScreensaver() {
        returnTo(R.string.test_screensaver, dream = !galleryOn())
        startScreensaver()
    }

    /** Nooko Launcher previews its own content; other screensavers use the system entry point. */
    private fun startScreensaver() {
        if (galleryOn()) return startActivity(Intent(this, GalleryPreview::class.java))
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            toast(getString(R.string.cant_dream))
        }
    }

    // ---------- updates (GitHub releases) ----------
    // Checked once a day from the home screen; a new version is announced once, then waits in settings.
    // The APK goes to a file in the cache, then into an install session. TVs without the confirm box open that file in
    // their own installer (see ApkProvider). The file is deleted when an update fails or is cancelled, and by the new
    // version when it starts (see Updated).

    @Volatile internal var checking = false
    @Volatile internal var updating = false

    internal fun myVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    /** The newer version a check found, if any (null again once it's installed). */
    internal fun availableUpdate() = prefs.getString("upd_ver", null)?.takeIf { newerVersion(it, myVersion()) }

    internal fun checkUpdate(force: Boolean) {
        if (checking || !force && System.currentTimeMillis() - prefs.getLong("upd_time", 0) < 24 * 3600_000L) return
        checking = true
        if (force) renderSettings(R.string.updates)
        io.execute {
            val latest = try {
                getJson("https://api.github.com/repos/$REPO/releases/latest").getString("tag_name").removePrefix("v")
            } catch (e: Exception) {
                android.util.Log.w("Nooko Launcher", "update check failed ($e)")
                null
            }
            if (latest != null) prefs.edit().putString("upd_ver", latest).putLong("upd_time", System.currentTimeMillis()).apply()
            ui.post {
                if (isDestroyed) return@post
                checking = false
                val ver = availableUpdate()
                if (ver != null && (force || prefs.getString("upd_told", null) != ver)) {
                    prefs.edit().putString("upd_told", ver).apply()
                    if (!force) toast(getString(R.string.update_available_toast, ver), long = true)
                } else if (force) {
                    toast(if (latest == null) getString(R.string.update_check_failed) else getString(R.string.up_to_date_toast))
                }
                updateBadge()
                if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
            }
        }
    }

    internal var transferringSettings = false

    internal fun confirmLoadSettings() = unlock(back = { showBackup() }) {
        if (settingsScrim.visibility != View.VISIBLE) showPanel()
        val path = try { SettingsTransfer.where(this) } catch (e: Exception) {
            toast(getString(R.string.settings_transfer_failed), long = true)
            return@unlock
        }
        showPage(getString(R.string.load_settings), listOf(
            page(getString(R.string.load_settings), "") { transferSettings(true) },
            page(getString(R.string.cancel), "") { showBackup() },
        ), top = label(getString(R.string.settings_load_warning, path), 14f).apply { maxLines = 12; ellipsize = null }) {
            showBackup()
        }
    }

    /** A What's new entry with a bullet, its **marked** words bold and the marks removed. */
    internal fun noteText(entry: String): SpannableString {
        val parts = entry.split("**")
        val s = SpannableString(parts.joinToString(""))
        var at = 0
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) s.setSpan(StyleSpan(Typeface.BOLD), at, at + part.length, 0)
            at += part.length
        }
        s.setSpan(BulletSpan(dp(10), fg), 0, s.length, 0)
        return s
    }

    /** Notes the user hasn't opened since updating. A fresh install has nothing new to tell. */
    internal fun notesUnseen(): Boolean {
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.firstInstallTime != info.lastUpdateTime && prefs.getString("seen_notes", null) != info.versionName
    }

    private val badge by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    /** A dot on the Nooko Launcher settings icon while an update waits or its notes are unread. */
    internal fun updateBadge() {
        val icon = findViewById<ImageView>(R.id.launcherSettings)
        icon.overlay.clear()
        if (availableUpdate() == null && !notesUnseen()) return
        val size = dp(9)
        badge.setBounds(icon.width - size - dp(3), dp(3), icon.width - dp(3), dp(3) + size)
        icon.overlay.add(badge)
    }

    /** The install session reports back here: show Android's confirm box (or the TV's own installer), or say why it failed. */
    private fun installStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            try {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { startActivity(it) }
                return
            } catch (_: ActivityNotFoundException) {
                // Some TVs (Xiaomi HyperOS) have no confirm box: their own installer takes the file instead.
                return openApk()
            }
        }
        updating = false
        if (status != PackageInstaller.STATUS_SUCCESS) {
            File(cacheDir, ApkProvider.NAME).delete()
            val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            android.util.Log.w("Nooko Launcher", "update install failed: $status $why")
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                toast(getString(R.string.update_install_failed), long = true)
            }
        }
        if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
    }

    /** Opens the file in the TV's own installer, for a TV without the confirm box. */
    private fun openApk() {
        updating = false
        try {
            startActivityForResult(Intent(Intent.ACTION_VIEW)
                .setDataAndType(ApkProvider.uri(this), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), INSTALL_REQUEST)
        } catch (_: Exception) {
            installerClosed()
            toast(getString(R.string.update_install_failed), long = true)
        }
        if (settingsScrim.visibility == View.VISIBLE) renderSettings(R.string.updates)
    }

    /**
     * Only now drops the waiting session: dropping it sends its result back to this activity, which would bring Nooko Launcher
     * back over the installer. After a successful update Nooko Launcher restarts instead, and Updated drops it.
     */
    private fun installerClosed() {
        dropSessions(this)
        File(cacheDir, ApkProvider.NAME).delete()
    }

    // ---------- weather (under the date) ----------
    // Location comes from the public IP (ipwho.is): a TV has no GPS and Android TV no location service.
    // Weather is Open-Meteo. Both are free and keyless. We always fetch Celsius and convert, so
    // switching units needs no refetch. The last reading is kept in prefs and shows instantly.

    internal val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, 30 * 60_000L)
        }
    }

    private fun refreshWeather() {
        if (prefs.getString("weather", "off") == "off") return
        if (System.currentTimeMillis() - prefs.getLong("wx_time", 0) < 25 * 60_000L) return
        io.execute {
            try {
                if (!manualCity() && System.currentTimeMillis() - prefs.getLong("geo_time", 0) > 6 * 3600_000L) {
                    val geo = getJson("https://ipwho.is/?fields=success,city,latitude,longitude")
                    if (geo.optBoolean("success")) prefs.edit()
                        .putString("geo_city", geo.getString("city"))
                        .putFloat("geo_lat", geo.getDouble("latitude").toFloat())
                        .putFloat("geo_lon", geo.getDouble("longitude").toFloat())
                        .putLong("geo_time", System.currentTimeMillis()).apply()
                }
                if (!prefs.contains("geo_lat")) return@execute
                val now = getJson("https://api.open-meteo.com/v1/forecast?latitude=${prefs.getFloat("geo_lat", 0f)}" +
                    "&longitude=${prefs.getFloat("geo_lon", 0f)}&current=temperature_2m,weather_code,is_day").getJSONObject("current")
                prefs.edit()
                    .putFloat("wx_temp", now.getDouble("temperature_2m").toFloat())
                    .putInt("wx_code", now.getInt("weather_code"))
                    .putBoolean("wx_day", now.optInt("is_day", 1) == 1)
                    .putLong("wx_time", System.currentTimeMillis()).apply()
                ui.post { if (!isDestroyed) showWeather() }
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "weather failed ($e)") // keep the last reading; next tick retries
            }
        }
    }

    // An IP's location is wherever the provider routes it, sometimes a whole region off (Rome showing as
    // Sicily), so you can pick the city instead. Open-Meteo's geocoding, also free and keyless.

    /** A one-line text box for the top of a settings page, inside its own holder; [done] gets the trimmed text. */
    internal fun textField(text: String, hint: String, flags: Int, ime: Int, done: (String) -> Unit): EditText {
        val field = EditText(this).apply {
            id = View.generateViewId()
            setText(text)
            setSelection(text.length)
            this.hint = hint
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or flags
            imeOptions = ime
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.argb(80, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
            setOnEditorActionListener { v, _, _ -> done(v.text.toString().trim()); true }
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
        }
        return field
    }

    internal fun manualCity() = prefs.getBoolean("geo_manual", false)

    internal class City(val name: String, val where: String, val lat: Float, val lon: Float)

    /** Automatic first, then the search box; a search lists what it found under them. */
    internal fun pickCity(query: String = "", found: List<City>? = null, status: String = "") {
        val field = textField(query, getString(R.string.city_hint), InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorInfo.IME_ACTION_SEARCH) { searchCity(it) }
        val top = (field.parent as LinearLayout).apply {
            if (status.isNotEmpty()) addView(label(status, 12f).apply { alpha = 0.6f; setPadding(dp(14), dp(8), 0, 0) })
        }
        val rows = listOf(page(getString(R.string.automatic_ip), if (manualCity()) "" else CHOSEN) {
            prefs.edit().remove("geo_manual").remove("geo_time").remove("wx_time").apply()
            refreshWeather()
            renderSettings(R.string.weather_location)
        }) + found.orEmpty().map { c ->
            page(c.name, c.where) {
                prefs.edit().putBoolean("geo_manual", true).putString("geo_city", c.name)
                    .putFloat("geo_lat", c.lat).putFloat("geo_lon", c.lon).remove("wx_time").apply()
                refreshWeather()
                renderSettings(R.string.weather_location)
            }
        }
        showPage(getString(R.string.weather_location), rows, if (found.isNullOrEmpty()) 0 else 1, top = top) { renderSettings(R.string.weather_location) }
        // showPage pins Up on the first row to itself; here Up goes to the search box.
        settingsList.getChildAt(1).nextFocusUpId = field.id
        if (found.isNullOrEmpty()) field.requestFocus()
        // Results in: put the keyboard away, it would cover them.
        else getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun searchCity(query: String) {
        if (query.isEmpty()) return
        pickCity(query, status = getString(R.string.searching))
        io.execute {
            val found = try {
                val r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
                    "&language=${java.util.Locale.getDefault().language}&name=${URLEncoder.encode(query, "UTF-8")}")
                val list = r.optJSONArray("results")
                (0 until (list?.length() ?: 0)).map { i ->
                    val c = list!!.getJSONObject(i)
                    City(c.getString("name"), listOf(c.optString("admin1"), c.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
                        c.getDouble("latitude").toFloat(), c.getDouble("longitude").toFloat())
                }
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (isDestroyed) return@post
                // Only if the page is still open: Back may have left it while the search ran.
                if (settingsScrim.visibility != View.VISIBLE || findViewById<TextView>(R.id.settingsTitle).text.toString() != getString(R.string.weather_location)) return@post
                when {
                    found == null -> pickCity(query, status = getString(R.string.search_failed))
                    found.isEmpty() -> pickCity(query, status = getString(R.string.no_places, query))
                    else -> pickCity(query, found)
                }
            }
        }
    }

    internal fun showWeather() {
        val view = findViewById<TextView>(R.id.weather)
        val unit = prefs.getString("weather", "off")
        if (unit == "off" || !prefs.contains("wx_temp")) {
            view.visibility = View.GONE
            return
        }
        val c = prefs.getFloat("wx_temp", 0f)
        val temp = Math.round(if (unit == "f") c * 9 / 5 + 32 else c)
        // Sits after the date on the same line: an icon for the sky, then the temperature. The city stays in settings.
        val code = prefs.getInt("wx_code", 0)
        view.text = "$temp°"
        view.contentDescription = "$temp° ${weatherWord(code)}"
        val icon = getDrawable(weatherIcon(code, prefs.getBoolean("wx_day", true)))!!.mutate().apply { setBounds(0, 0, dp(18), dp(18)) }
        view.setCompoundDrawablesRelative(icon, null, null, null)
        view.compoundDrawableTintList = ColorStateList.valueOf(view.currentTextColor)
        view.visibility = View.VISIBLE
        view.post { fitInk() }
    }

    /** The same WMO codes as icons. Clear and partly cloudy have a night version. */
    /** WMO weather codes, as Open-Meteo reports them. */
    private fun weatherWord(code: Int) = when (code) {
        0 -> getString(R.string.wx_clear)
        1, 2 -> getString(R.string.wx_partly_cloudy)
        3 -> getString(R.string.wx_cloudy)
        45, 48 -> getString(R.string.wx_fog)
        in 51..57 -> getString(R.string.wx_drizzle)
        in 61..67, in 80..82 -> getString(R.string.wx_rain)
        in 71..77, 85, 86 -> getString(R.string.wx_snow)
        in 95..99 -> getString(R.string.wx_thunderstorm)
        else -> ""
    }

    /** Always fresh from the web: the picture cache must not answer for the update check, the weather or a place search. */
    private fun getJson(url: String): JSONObject {
        val r = Http.send(url, connectMs = 5000, readMs = 8000)
        if (!r.ok) throw IOException("HTTP ${r.code} for $url")
        return JSONObject(r.body)
    }

    // ---------- continue watching (Android TV Watch Next) ----------

    internal fun refreshWatchNext() {
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("Nooko Launcher", "continue watching: no $TV_LISTINGS")
            showWatchNext(Cards(emptyList(), null, emptyList()))
            return
        }
        io.execute {
            val cards = try {
                queryWatchNext()
            } catch (e: Exception) {
                android.util.Log.w("Nooko Launcher", "continue watching failed ($e)")
                Cards(emptyList(), null, emptyList())
            }
            ui.post { if (!isDestroyed) showWatchNext(cards) }
        }
    }

    /**
     * Two sources, same columns:
     * 1. The system Watch Next list (Netflix etc.), newest engagement first.
     * 2. Apps' own home-screen channels the user switched on (see [channelOn]); ones named "Continue Watching"
     *    are on to start with. Stremio publishes one (with progress and an autoPlay resume link) instead of
     *    using Watch Next, so signing in to Stremio on the TV is all it takes. Channel rows sort by their own
     *    last-watched time when the app sets one, else by the time the app was last opened from Nooko Launcher, see [channelSortKey].
     * Newest first across apps, each app's [PER_APP] newest ahead of the rest, so one app with ten shows can't bury the others.
     */
    private fun queryWatchNext(): Cards {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
        val watchNextOn = WATCH_NEXT !in prefs.getStringSet("ch_flip", emptySet())!!
        val watchNext = if (watchNextOn) readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden) else emptyList()
        val on = continueChannels()
        autoRowName = if (watchNextOn) null
            else on.map { it.third }.distinct().singleOrNull()?.takeUnless { it.equals("Continue Watching", ignoreCase = true) }
        val off = prefs.getStringSet("cw_off", emptySet())!!
        if (cardsMode() != ShowRows.MIXED) return splitCards(watchNext, on, hidden, off)
        val channels = on.flatMap { (id) ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden, "ch$id")
        }
        val all = watchNext + channels
        android.util.Log.i("Nooko Launcher", "continue watching: ${watchNext.size} from Watch Next, ${channels.size} from ${on.size} channels")
        publishingPkgs = all.mapNotNull { it.second.pkg }.toSet()
        return Cards(emptyList(), null, newestPerApp(all.filter { it.second.pkg !in off }.distinctBy { it.second.intent ?: it.second.title }, PER_APP) { it.group }
            .take(ROW_CARDS))
    }

    /**
     * By title and Per channel: a list of cards for Watch Next and one for each channel that is on, each in its own order
     * (the same rules as the mixed row: hidden shows and turned-off apps left out, no repeats, at most [ROW_CARDS]).
     * A source with nothing in it is left out. [ShowRows.ordered] puts Watch Next first, then the Continue Watching channels.
     */
    private fun splitCards(watchNext: List<Pair<Long, Prog>>, on: List<Triple<Long, String, String>>, hidden: Set<String>, off: Set<String>): Cards {
        val label = apps.associate { it.pkg to it.label }
        val found = ArrayList<Pair<ShowRows.Source, List<Prog>>>()
        val seen = HashSet<String>()
        fun add(key: String, title: String, kind: ShowRows.Kind, items: List<Pair<Long, Prog>>) {
            seen += items.mapNotNull { it.second.pkg }
            val shown = sourceCards(items, PER_APP, ROW_CARDS, { it.group }, { it.pkg in off }) { it.intent ?: it.title }
            if (shown.isNotEmpty()) found += ShowRows.Source(key, title, kind) to shown
        }
        add(WATCH_NEXT, getString(R.string.watch_next_tab), ShowRows.Kind.WATCH_NEXT, watchNext)
        for ((id, p, n) in on.distinctBy { (_, p, n) -> channelKey(p, n) }) {
            val kind = if (n.equals("Continue Watching", ignoreCase = true)) ShowRows.Kind.CONTINUE else ShowRows.Kind.OTHER
            // The app list can still be empty on the first read after a start: ask the package manager, or the name loses its app.
            val app = appName(label, p)
            add(channelKey(p, n), if (n == app || app.isNullOrBlank()) n else "$app · $n", kind,
                readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden, "ch$id"))
        }
        publishingPkgs = seen
        val rows = ShowRows.ordered(found.map { it.first }).map { s -> found.first { it.first === s } }
        val at = ShowRows.current(rows.map { it.first.key }, prefs.getString("cards_src", null))
        val current = rows.firstOrNull { it.first.key == at }
        // The first posters of the other sources load in the background, so a switch finds them ready.
        val ahead = rows.filter { it !== current }.flatMap { r -> r.second.take(4).mapNotNull { it.image } }
            .take(8) // ponytail: the 6 MB poster cache holds ~18; more ahead would push out the row on show
        return Cards(rows.map { CardSource(it.first.key, it.first.title) }, current?.let { CardSource(it.first.key, it.first.title) }, current?.second ?: emptyList(), ahead)
    }

    /** Apps last seen publishing, so the settings list includes them even without the permission check. */
    @Volatile private var publishingPkgs = emptySet<String>()

    /**
     * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
     * actually publishing now. Built from installed apps each time, so new apps appear on their own.
     */
    private fun watchSources(): List<App> = apps.filter { a ->
        a.pkg in publishingPkgs || try {
            packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(WRITE_EPG) == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }.sortedBy { it.label.lowercase() }

    internal fun pickWatchSources() {
        val sources = watchSources()
        if (sources.isEmpty()) {
            toast(getString(R.string.no_cw_apps))
            return
        }
        toggles(getString(R.string.cw_apps), sources.map { it.label to it.pkg }, "cw_off", getString(R.string.on), getString(R.string.off)) {
            watchKey = null
            refreshWatchNext()
            renderSettings(R.string.cw_apps)
        }
    }

    /** Every other app's home-screen channel as (id, package, name); empty where Android won't let us read them. */
    internal fun appChannels(): List<Triple<Long, String, String>> = try {
        val out = ArrayList<Triple<Long, String, String>>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val name = c.getColumnIndex("display_name")
            val pkg = c.getColumnIndex("package_name")
            val id = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                val p = c.getString(pkg) ?: continue
                val n = c.getString(name) ?: continue
                if (p != packageName) out += Triple(c.getLong(id), p, n)
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    internal fun channelKey(pkg: String, name: String) = "$pkg/$name"

    private fun continueChannels(): List<Triple<Long, String, String>> {
        val flipped = prefs.getStringSet("ch_flip", emptySet())!!
        return appChannels().filter { (_, p, n) -> channelOn(n, channelKey(p, n), flipped) }
    }

    /** The one channel on, unless it's a "Continue Watching" one: the bottom row is named after it. */
    @Volatile private var autoRowName: String? = null

    /** The bottom row's name: the user's own, else the one channel on, else Continue Watching. */
    internal fun rowName() = prefs.getString("row_name", null).orEmpty().ifEmpty { autoRowName ?: getString(R.string.continue_watching) }

    internal fun pickRowName() {
        val field = textField(prefs.getString("row_name", null).orEmpty(), autoRowName ?: getString(R.string.continue_watching),
            InputType.TYPE_TEXT_FLAG_CAP_WORDS, EditorInfo.IME_ACTION_DONE) { name ->
            prefs.edit().putString("row_name", name).apply()
            showTitle()
            renderSettings(R.string.row_name)
        }
        val auto = page(getString(R.string.automatic), if (prefs.getString("row_name", null).isNullOrEmpty()) CHOSEN else "") {
            prefs.edit().remove("row_name").apply()
            showTitle()
            renderSettings(R.string.row_name)
        }
        // Opens on Automatic, so the keyboard only comes up when you go Up to the box to type.
        showPage(getString(R.string.row_name), listOf(auto), top = field.parent as View) { renderSettings(R.string.row_name) }
        settingsList.getChildAt(1).nextFocusUpId = field.id
    }

    /** Pick what fills the bottom row: Android TV Watch Next and any app channel on this TV now ("Plex · On Deck"). */
    internal fun pickChannels() {
        val channels = appChannels().distinctBy { (_, p, n) -> channelKey(p, n) }
        val label = apps.associate { it.pkg to it.label }
        fun app(p: String) = appName(label, p) ?: p
        val items = listOf(getString(R.string.watch_next) to WATCH_NEXT) +
            channels.map { (_, p, n) -> (if (n == app(p)) n else "${app(p)} · $n") to channelKey(p, n) }.sortedBy { it.first.lowercase() }
        val startsOff = channels.filter { (_, p, n) -> !channelOn(n, channelKey(p, n), emptySet()) }.map { (_, p, n) -> channelKey(p, n) }.toSet()
        toggles(getString(R.string.cw_channels), items, "ch_flip", getString(R.string.on), getString(R.string.off), startsOff = startsOff) {
            watchKey = null
            refreshWatchNext()
            renderSettings(R.string.cw_channels)
        }
    }

    /**
     * Only the columns [readPrograms] reads. A null projection makes the TV provider select every column Android
     * knows, split_id included (added in Android 12); some TVs upgraded to 12 (Philips 2019 OLEDs) never added it to
     * watch_next_programs, so the whole query failed and Continue Watching stayed empty.
     * weight exists only for preview programs; watch_next_type and the engagement time only for Watch Next.
     */
    private fun programColumns(uri: Uri): Array<String> {
        val common = arrayOf("browsable", "title", "package_name", "season_display_number", "episode_display_number",
            "duration_millis", "last_playback_position_millis", "thumbnail_uri", "poster_art_uri", "poster_art_aspect_ratio",
            "type", "intent_uri")
        return if (uri.pathSegments.firstOrNull() == TvContract.WatchNextPrograms.CONTENT_URI.pathSegments.first())
            common + arrayOf("watch_next_type", "last_engagement_time_utc_millis")
        else common + "weight"
    }

    /** Pairs of (sort key, item), see [watchSortKey]; a [group] is a channel, its rows sorted by [channelSortKey] and [byWeight]. */
    private fun readPrograms(uri: Uri, hidden: Set<String>, group: String? = null): List<Pair<Long, Prog>> {
        val out = ArrayList<Triple<Long, Long, Prog>>()
        val cursor = contentResolver.query(uri, programColumns(uri), null, null, null)
        if (cursor == null) android.util.Log.w("Nooko Launcher", "no TV provider answer for $uri") // provider hidden from us or missing
        cursor?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
            fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                if (str("browsable") == "0") continue
                val title = str("title") ?: continue
                if (hideKey(str("package_name"), title) in hidden) continue
                val season = displayNumber(str("season_display_number"))
                val episode = displayNumber(str("episode_display_number"))
                val duration = num("duration_millis")
                val position = num("last_playback_position_millis")
                val sub = listOfNotNull(
                    if (season != null && episode != null) getString(R.string.season_episode, season, episode) else null,
                    // Under a second in means "not really started": no "time left", no bar.
                    if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
                ).joinToString(" - ")
                val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                val pkg = str("package_name")
                val thumbnail = str("thumbnail_uri")
                val poster = str("poster_art_uri")
                val widePoster = poster?.takeIf { num("poster_art_aspect_ratio").toInt() in 0..2 } // 16:9, 3:2 and 4:3 are wide enough
                val tmdbType = when (str("type")?.toIntOrNull()) {
                    TvContract.PreviewPrograms.TYPE_MOVIE -> "movie"
                    TvContract.PreviewPrograms.TYPE_TV_SERIES, TvContract.PreviewPrograms.TYPE_TV_SEASON,
                    TvContract.PreviewPrograms.TYPE_TV_EPISODE -> "tv"
                    null -> null
                    else -> ""
                }
                val prog = Prog(title, sub, progress, thumbnail ?: poster, str("intent_uri"), pkg, group ?: pkg,
                    listOfNotNull(widePoster, thumbnail).distinct(), tmdbType)
                val engaged = num("last_engagement_time_utc_millis")
                out += Triple(num("weight"),
                    if (group == null) watchSortKey(num("watch_next_type"), engaged) else channelSortKey(engaged, prefs.getLong("t_$pkg", 0)), prog)
            }
        }
        return byWeight(out)
    }

    /** "mixed" (every source in one row), "title" (one row, switched with its title) or "stack" (one row per source, Down and Up). */
    internal fun cardsMode() = ShowRows.mode(prefs.getString("cards_rows", null))

    /**
     * The Rows page: one line for each way to group the shows, to select (not a value to step through). The picked one has a
     * tick, and the help line under the list says what it does and which keys to press.
     */
    internal fun pickCardsRows(focusAt: Int = CARDS_ROWS.keys.indexOf(cardsMode())) {
        val current = cardsMode()
        val help = mapOf(ShowRows.MIXED to R.string.help_cards_mixed, ShowRows.BY_TITLE to R.string.help_cards_title, ShowRows.PER_CHANNEL to R.string.help_cards_each)
        val rows = CARDS_ROWS.map { (mode, name) ->
            page(getString(name), if (mode == current) CHOSEN else "") {
                prefs.edit().putString("cards_rows", mode).apply()
                watchKey = null
                refreshWatchNext()
                pickCardsRows()
            }
        }
        // Split by source with one source only, the row looks like Mixed: say so, or the choice seems to do nothing.
        val note = getString(R.string.cards_rows_note) + if (current != ShowRows.MIXED && cardSources.size == 1) " " + getString(R.string.cards_rows_one) else ""
        showPage(getString(R.string.cards_rows), rows, focusAt, styles = CARDS_ROWS.keys.map { RowStyle(help = getString(help.getValue(it))) },
            top = label(note, 15f).apply {
                maxLines = 5
                setPadding(dp(14), dp(4), dp(14), dp(10))
            }) {
            renderSettings(R.string.cards_rows)
        }
        cardsPage = true // the sources are known a moment after a choice: the note is drawn again then (see showWatchNext)
        pageExit = { cardsPage = false }
    }

    private var cardsPage = false

    /** By title with at least two sources: the title is a pill you can select. */
    private fun canCycle() = cardsMode() == ShowRows.BY_TITLE && cardSources.size > 1

    /** By title: Left or Right on the title shows the next or the previous source. */
    private fun cycleSource(d: Int) {
        switchX = d; switchY = 0
        switchTo(ShowRows.beside(cardSources.map { it.key }, cardCurrent?.key, d) ?: return) // stops at the ends, as the arrows show
    }

    /** Per channel: Down on a card shows the next row (+1), Up the one before (-1). False when there is none that way. */
    private fun pageSource(d: Int): Boolean {
        switchX = 0; switchY = d
        switchTo(ShowRows.beside(cardSources.map { it.key }, cardCurrent?.key, d) ?: return false)
        return true
    }

    private fun switchTo(key: String) {
        prefs.edit().putString("cards_src", key).apply()
        switching = true
        // The title answers the key at once; the cards follow when the new list is read.
        cardSources.firstOrNull { it.key == key }?.let { cardCurrent = it; showTitle(fade = true) }
        refreshWatchNext()
    }

    /** Where the new content of a source change comes from: +1 right or below, -1 left or above (the key's direction). */
    private var switchX = 0
    private var switchY = 0

    /** What the row is called: its own name when mixed, else the source on show; Per channel adds an arrow to each other row. */
    private fun rowTitle(): String {
        val cur = cardCurrent
        if (cardsMode() == ShowRows.MIXED || cur == null) return rowName()
        return cur.title
    }

    /**
     * Split by source, the row has one title: the source on show, then a counter (dots up to five sources, "2 / 7" past that).
     * By title it can be selected: a glass chip that fits the name, with the arrows after the name. The chip's start edge
     * never moves, so the name sits on the grid edge, where the plain title sits, for every source.
     */
    private fun setUpTitle() {
        watchTitle.isFocusable = false
        watchTitle.isSingleLine = true
        watchTitle.compoundDrawablePadding = dp(8)
        watchTitle.setOnFocusChangeListener { _, focused ->
            watchTitle.chip = focused
            watchTitle.animate().alpha(if (focused) 1f else titleAlpha).setDuration(160).start()
            showTitle()
        }
        (watchTitle.parent as ViewGroup).clipChildren = false // the chip can shrink past the label's new width
    }

    /** Change the title without interrupting the row's visibility or focus animations. */
    private fun showTitle(fade: Boolean = false) {
        val cycle = canCycle()
        watchTitle.isFocusable = cycle
        val ink = watchTitle.currentTextColor
        watchTitle.chipColor = if (Color.luminance(ink) > 0.5f) 0x38FFFFFF else 0x24000000
        val at = cardSources.indexOfFirst { it.key == cardCurrent?.key }
        // Arrows only while selected, one for each way that has another source, both after the name (left one on the left, as
        // the remote's keys), so the chip's start edge stays in one place for every source.
        val ids = listOfNotNull(R.drawable.ms_chevron_left.takeIf { at > 0 }, R.drawable.ms_chevron_right.takeIf { at in 0 until cardSources.size - 1 })
        val c = dp(CHEVRON)
        val arrows = if (!cycle || ids.isEmpty()) null else LayerDrawable(ids.map { getDrawable(it)!!.mutate().apply { setTint(ink) } }.toTypedArray()).apply {
            ids.indices.forEach { setLayerSize(it, c, c); setLayerGravity(it, Gravity.LEFT or Gravity.TOP); setLayerInset(it, it * (c + dp(4)), 0, 0, 0) }
            setBounds(0, 0, ids.size * c + (ids.size - 1) * dp(4), c)
            alpha = if (watchTitle.isFocused) 255 else 0
        }
        watchTitle.setCompoundDrawablesRelative(null, null, arrows, null)
        val side = if (cycle) dp(12) else 0
        watchTitle.setPaddingRelative(side, dp(4), side, dp(4))
        val lp = watchTitle.layoutParams as LinearLayout.LayoutParams
        val start = -side
        if (lp.marginStart != start) { lp.marginStart = start; watchTitle.layoutParams = lp }
        val room = ((resources.displayMetrics.widthPixels - dp(96)) * 2 / 3).toFloat()
        val name = TextUtils.ellipsize(rowTitle(), watchTitle.paint, room, TextUtils.TruncateAt.END)
        val text: CharSequence = if (cardsMode() == ShowRows.MIXED || cardSources.size < 2 || at < 0) name else {
            val count = if (cardSources.size <= 5) cardSources.indices.joinToString(" ") { if (it == at) "●" else "○" }
                else "${at + 1} / ${cardSources.size}"
            SpannableStringBuilder(name).append("   ").also {
                val from = it.length
                it.append(count)
                if (cardSources.size <= 5) it.setSpan(android.text.style.RelativeSizeSpan(0.75f), from, it.length, 0)
                it.setSpan(ForegroundColorSpan((ink and 0xFFFFFF) or (0x99 shl 24)), from, it.length, 0)
            }
        }
        val shift = dp(16).toFloat()
        watchTitle.change(text, if (fade) TITLE_SLIDE_MS else 0, offsetX = switchX * shift, offsetY = switchY * shift)
    }

    private fun hideKey(pkg: String?, title: String) = "$pkg/$title"

    /** We can't delete another app's Watch Next row, so hiding is a local filter. */
    private fun hideWatch(p: Prog) {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!! + hideKey(p.pkg, p.title)
        prefs.edit().putStringSet("hidden_watch", hidden).apply()
        toast(getString(R.string.hidden_from_cw))
        refreshWatchNext()
    }

    /** Small source-app mark for the details line: its logo when we have one (brand colour with colour icons), else its icon (greyscale unless colour). */
    internal fun sourceIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        val colour = cwColour()
        val tint = BRAND_COLOURS[pkg]?.takeIf { colour } ?: watchInk // a mark without its brand colour goes with the card's text
        LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(tint) } }
        return try {
            packageManager.getApplicationIcon(pkg).mutate().apply { if (!colour) colorFilter = GREYSCALE }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun timeLeft(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) getString(R.string.time_left_hm, min / 60, min % 60) else getString(R.string.time_left_m, min)
    }

    internal fun showWatchNext(cards: Cards) {
        val list = cards.list
        val key = ShowRows.stamp(cards.sources.map { it.key to it.title }, cards.current?.key, list.map { "${it.title}/${it.sub}/${it.progress}/${it.image}" })
        if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
            retryPosters()
            return
        }
        // Another source of the same row: the title and the cards stay where they are and change their content.
        val inPlace = switching && watchRow.childCount > 0 && list.isNotEmpty() && cardCurrent != null
        switching = false
        watchKey = key
        watchList = list
        cardSources = cards.sources
        cardCurrent = cards.current
        for (uri in cards.ahead) if (posters.get(uri) == null && loading.add(uri)) posterIo.execute {
            try { decode(uri)?.let { posters.put(uri, it) } } catch (e: Exception) { android.util.Log.w("TvHome", "poster failed: $uri ($e)") } finally { loading.remove(uri) }
        }
        if (cardsPage) pickCardsRows((settingsList.indexOfChild(currentFocus) - 1).coerceIn(0, CARDS_ROWS.size - 1)) // child 0 is the note
        if (inPlace) { // no wait for posters: each one fades in on its card when it is there
            showTitle(fade = true)
            swapCards()
            return
        }
        showTitle()
        val focusedAt = watchRow.indexOfChild(currentFocus?.parent as? View)
        val focusedKey = (currentFocus?.getTag(R.id.watchRow) as? Prog)?.let { cardKey(it) } // the same show, whatever place it has now
        watchRow.removeAllViews()
        placeRow(animate = false)
        // ~16 ms per card here and only four show at once: the first four now, the next four on the next frame.
        addCardsTo(3)
        watchRow.post { fitInk() }
        ui.post { if (watchKey == key) addCardsTo(CARDS_AHEAD - 1) } // unless a newer list replaced this one
        if (focusedAt >= 0) {
            val at = watchList.indexOfFirst { cardKey(it) == focusedKey }.takeIf { it >= 0 } ?: focusedAt.coerceAtMost(watchList.size - 1)
            addCardsTo(at) // the card may be past the first ones that are built
            ((watchRow.getChildAt(at) as? LinearLayout)?.getChildAt(0) ?: appRow.getChildAt(0))?.requestFocus()
        }
    }

    internal var watchList = emptyList<Prog>()

    /** "always", "down" (hidden until Down reaches it) or "never". */
    internal fun rowShow() = prefs.getString("row_show", "always")!!.takeIf { it in ROW_SHOW } ?: "always"

    /** While the bottom row is hidden, the app row drops into its space (true) or stays where it is. */
    internal fun rowDrops() = prefs.getBoolean("row_drop", false)

    /** "Icons" in the shows row: source marks in colour (true) or monochrome, apart from the app row's icon style. */
    internal fun cwColour() = prefs.getBoolean("cw_colour", false)

    /**
     * Puts the bottom row and the app row where "Show row" wants them. Both rows are built once and only
     * slide (translationY on a layer), so the move costs no layout or redraw, even on a slow TV.
     */
    internal fun placeRow(animate: Boolean) {
        val show = rowShow()
        val has = watchList.isNotEmpty()
        val keepSpace = show != "always" && !rowDrops() // "Stays put": the apps keep their place, even with no shows
        // With no shows, an invisible stand-in card holds the row's height, so the apps don't fall to the bottom.
        if (!has && keepSpace && watchRow.childCount == 0) watchRow.addView(watchCard(Prog(" ", " ", 0, null, null, null), false))
        else if (!has && !keepSpace && watchRow.childCount > 0) watchRow.removeAllViews()
        val vis = when {
            !has -> if (keepSpace) View.INVISIBLE else View.GONE
            show == "never" -> if (rowDrops()) View.GONE else View.INVISIBLE // invisible keeps its space
            else -> View.VISIBLE
        }
        if (watchScroll.visibility != vis) { watchTitle.visibility = vis; watchScroll.visibility = vis }
        val hidden = show == "down" && has && !rowOut
        // From where the app row sits above the bottom row to where it would sit without it.
        val drop = (watchScroll.bottom - appScroll.bottom).toFloat().coerceAtLeast(0f)
        val appY = if (hidden && rowDrops()) drop else 0f
        val rowY = if (!hidden) 0f else if (rowDrops()) drop else dp(24).toFloat()
        fun move(v: View, y: Float, a: Float) {
            if (v.translationY == y && v.alpha == a) return // a layer for a view that doesn't move only costs frames
            if (animate) v.animate().translationY(y).alpha(a).setDuration(250).setInterpolator(DECELERATE).start()
            else { v.animate().cancel(); v.translationY = y; v.alpha = a }
        }
        move(appsTitle, appY, appsTitle.alpha)
        move(appScroll, appY, 1f)
        move(watchTitle, rowY, if (hidden) 0f else if (watchTitle.isFocused) 1f else titleAlpha)
        move(watchScroll, rowY, if (hidden) 0f else 1f)
        // The rows are layers: they move and fade without a layout, so the frost has to be told to follow them.
        glass.kick(if (animate) 320 else 0)
    }

    /**
     * Cards exist up to index [last] only: the four on screen and the four after them, more as focus moves on.
     * Every card costs drawing time on each frame of the home screen (20 dropped ~6% of frames on the test
     * TV, 8 about 2%), on screen or not, so the rest of the list waits until focus gets near.
     */
    private fun addCardsTo(last: Int) {
        while (watchRow.childCount <= minOf(last, watchList.size - 1)) {
            watchRow.addView(watchCard(watchList[watchRow.childCount], watchRow.childCount > 0))
        }
    }

    /**
     * Four posters across, each two apps-row columns wide. Progress sits on the artwork's bottom edge;
     * title and details (source, episode, time left) go underneath.
     */
    private fun watchCard(p: Prog, gapBefore: Boolean): View {
        val gap = dp(GRID_GAP)
        val w = (resources.displayMetrics.widthPixels - dp(96) - gap * 3) / 4
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (gapBefore) marginStart = gap
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(if (dark) DARK_SURFACE else 0xFFDDE1E5.toInt())
        }
        // Long titles scroll while their poster is focused instead of staying cut off.
        val title = TextView(this).apply {
            text = p.title
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END // MARQUEE while focused
            marqueeRepeatLimit = -1
            ink(this, watchInk)
            // TextView widens its clip by the shadow, so a scrolling title would show past the card's sides.
            addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.clipBounds = Rect(0, -dp(4), r - l, b - t + dp(4)) }
        }
        val frame = FrameLayout(this)
        // The poster fills the card, so frost under it would never show and would only cost work on every frame.
        style(frame, dp(8), card = true, lift = 1.1f, frost = false) { focused ->
            title.isSelected = focused
            title.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
            if (focused) {
                lastCard = frame
                addCardsTo(watchRow.indexOfChild(col) + CARDS_AHEAD)
            }
        }
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)
        frame.setOnClickListener { (frame.getTag(R.id.watchRow) as? Prog)?.let { open(it) } }
        // 2:1 keeps logos and badges that providers bake into 16:9 art.
        col.addView(frame, LinearLayout.LayoutParams(w, w / 2))
        // Thin bar under the poster, card width. Kept (invisible) when there's no progress so titles line up.
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = progressBarDrawable(watchInk)
        }, LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(8) })
        col.addView(title)
        col.addView(TextView(this).apply {
            text = p.sub
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ink(this, watchInk)
            alpha = 0.8f
            setPadding(0, dp(2), 0, 0)
            compoundDrawablePadding = dp(6)
        })
        bindCard(col, p)
        return col
    }

    /**
     * Keep the card and focus in place. Text and posters crossfade together.
     */
    private fun bindCard(col: ViewGroup, p: Prog, fade: Boolean = false, delay: Long = 0) {
        val frame = col.getChildAt(0) as ViewGroup
        val img = frame.getChildAt(0) as ImageView
        val bar = col.getChildAt(1) as ProgressBar
        val title = col.getChildAt(2) as TextView
        val sub = col.getChildAt(3) as TextView
        frame.setTag(R.id.watchRow, p) // holding OK hides it (see onHold)
        frame.contentDescription = p.title
        val icon = sourceIcon(p.pkg)
        bar.setProgress(p.progress, fade && ValueAnimator.areAnimatorsEnabled())
        bar.visibility = if (p.progress > 0) View.VISIBLE else View.INVISIBLE
        val slide = fade && ValueAnimator.areAnimatorsEnabled()
        val dx = switchX * dp(SLIDE).toFloat()
        val dy = switchY * dp(SLIDE).toFloat()
        fun swap(v: View, rest: Float, after: Long, set: () -> Unit) = if (slide) slideSwap(v, rest, delay + after, dx, dy, set) else set()
        swap(title, 1f, 0) { title.text = p.title }
        swap(sub, 0.8f, 20) {
            sub.text = p.sub
            icon?.setBounds(0, 0, dp(12), dp(12))
            sub.setCompoundDrawablesRelative(icon, null, null, null)
            // A plain mark (not the app's own icon, not in brand colour) follows the text, as fitInk tints it.
            sub.tag = if (LOGOS[p.pkg]?.second == false && (!cwColour() || BRAND_COLOURS[p.pkg] == null)) INK_MARK else null
            if (sub.tag == INK_MARK) icon?.setTint(watchInk)
            // Split by source, a card without details keeps their space, or the row would change height with the source.
            sub.visibility = if (p.sub.isNotEmpty() || icon != null) View.VISIBLE else if (cardsMode() == ShowRows.MIXED) View.GONE else View.INVISIBLE
        }
        if (!slide || img.getTag(R.id.poster_loaded) == p.image) loadPoster(img, p.image)
        else swap(img, 1f, 0) {
            // Out of sight now: put the new poster in at once (no crossfade from the old one), or the empty card to fade into.
            val bmp = p.image?.let { posters.get(it) }
            if (bmp != null) { img.tag = p.image; img.setTag(R.id.poster_loaded, p.image); img.setImageDrawable(BitmapDrawable(resources, bmp)) }
            else { img.setImageDrawable(ColorDrawable(Color.TRANSPARENT)); img.setTag(R.id.poster_loaded, null); loadPoster(img, p.image) }
        }
    }

    /**
     * [v] moves out against the key's direction and fades, [set] changes its content while it is out of sight, then it comes in
     * from the other side to [rest] alpha. These are view properties, so the GPU moves what is drawn and nothing is drawn again
     * on each frame. The card frame clips the poster, so the card stays still and only its picture moves.
     */
    private fun slideSwap(v: View, rest: Float, delay: Long, dx: Float, dy: Float, set: () -> Unit) {
        v.animate().cancel()
        v.animate().translationX(-dx).translationY(-dy).alpha(0f).setStartDelay(delay).setDuration(SLIDE_OUT_MS)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
                set()
                v.translationX = dx; v.translationY = dy
                v.animate().translationX(0f).translationY(0f).alpha(rest).setStartDelay(0).setDuration(CARD_SLIDE_MS - SLIDE_OUT_MS)
                    .setInterpolator(CrossfadeLabel.EASE).withEndAction(null).start()
            }.start()
    }

    /**
     * Another source of the same row: the cards that are there change their content in place (title, poster, progress), the
     * ones the new source has more of fade in and the ones it has fewer of fade out. Nothing slides and the row keeps its place.
     */
    private fun swapCards() {
        for (i in watchRow.childCount - 1 downTo 0) if (watchRow.getChildAt(i).tag == FADING) watchRow.removeViewAt(i)
        val kept = minOf(watchRow.childCount, watchList.size)
        // A new source starts at its first show, at the start of the row.
        if (watchRow.hasFocus()) (watchRow.getChildAt(0) as ViewGroup).getChildAt(0).requestFocus()
        (watchScroll as? android.widget.HorizontalScrollView)?.smoothScrollTo(0, 0)
        // Only the cards on screen move, left to right; the ones past them change at once.
        for (i in 0 until kept) bindCard(watchRow.getChildAt(i) as ViewGroup, watchList[i], fade = i < 4, delay = i * STAGGER_MS)
        for (i in watchRow.childCount - 1 downTo kept) {
            val col = watchRow.getChildAt(i)
            if (col.hasFocus()) (watchRow.getChildAt(kept - 1) as ViewGroup).getChildAt(0).requestFocus()
            col.tag = FADING
            col.animate().alpha(0f).setDuration(SWAP_OUT_MS).withEndAction { watchRow.removeView(col) }.start()
        }
        val before = watchRow.childCount
        addCardsTo(3)
        for (i in before until watchRow.childCount) watchRow.getChildAt(i).apply { alpha = 0f; animate().alpha(1f).setStartDelay(i * STAGGER_MS).setDuration(CARD_SLIDE_MS).start() }
        placeRow(animate = false)
        watchRow.post { fitInk() }
        val key = watchKey
        ui.post { if (watchKey == key) addCardsTo(CARDS_AHEAD - 1) }
    }

    /** [ink] fills it; the track is a faint version on the same side, light behind white and dark behind near-black. */
    private fun progressBarDrawable(ink: Int = fg): Drawable {
        fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
        return LayerDrawable(arrayOf(
            bar(if (Color.luminance(ink) > 0.5f) 0x40FFFFFF else 0x26000000),
            ClipDrawable(bar(ink), Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    internal fun open(p: Prog) {
        prefs.edit().putLong("t_${p.pkg}", System.currentTimeMillis()).apply()
        val from = currentFocus?.takeIf { it.getTag(R.id.watchRow) is Prog }
        try {
            startApp(Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), from)
        } catch (e: Exception) {
            p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startApp(it, from) }
        }
    }

    internal fun loadPoster(v: ImageView, uri: String?) {
        v.tag = uri // a load still on its way for the show this card had finds another uri here and drops its picture
        if (uri == null) {
            v.setImageDrawable(null)
            v.setTag(R.id.poster_loaded, null)
            return
        }
        if (v.getTag(R.id.poster_loaded) == uri) return
        posters.get(uri)?.let { showPoster(v, uri, it); return }
        if (!loading.add(uri)) return // already on its way: posterArrived fills this card when it lands
        posterIo.execute {
            val bmp = try {
                decode(uri)
            } catch (e: Exception) {
                android.util.Log.w("TvHome", "poster failed: $uri ($e)") // retried by retryPosters()
                null
            } finally {
                loading.remove(uri)
            } ?: return@execute
            posters.put(uri, bmp)
            ui.post { posterArrived(uri) }
        }
    }

    /** A poster is in the cache: each card that wants it shows it, whichever load fetched it. */
    private fun posterArrived(uri: String) {
        if (isDestroyed) return
        val bmp = posters.get(uri) ?: return
        for (i in 0 until watchRow.childCount) {
            val img = ((watchRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
            if (img.tag == uri) showPoster(img, uri, bmp)
        }
        for (i in 0 until heroRow.childCount) {
            val img = (heroRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
            if (img.tag == uri) showPoster(img, uri, bmp)
        }
    }

    internal fun heroFull() = settings.tmdbFull && Tmdb.key(this) != null

    internal var heroCard: LinearLayout? = null

    private fun showPoster(v: ImageView, uri: String, bmp: Bitmap) {
        if (v.getTag(R.id.poster_loaded) == uri) return
        v.setTag(R.id.poster_loaded, uri)
        val next = BitmapDrawable(resources, bmp)
        val old = v.drawable.let { if (it is TransitionDrawable) it.getDrawable(1) else it }
        if (old == null || !ValueAnimator.areAnimatorsEnabled()) {
            v.setImageDrawable(next)
            return
        }
        val fade = TransitionDrawable(arrayOf(old, next)).apply { isCrossFadeEnabled = true }
        v.setImageDrawable(fade)
        // TransitionDrawable does not apply the system duration scale; match the text's ValueAnimator.
        val duration = (POSTER_FADE_MS * Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)).toLong()
        fade.startTransition(duration.toInt())
        ui.postDelayed({ if (!isDestroyed && v.drawable === fade) v.setImageDrawable(next) }, duration)
    }

    /** Cards whose poster never arrived (offline, server timeout) try again; loaded ones are left alone. */
    private fun retryPosters() {
        for (i in 0 until watchRow.childCount) {
            val img = ((watchRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
            if (img.tag != img.getTag(R.id.poster_loaded)) loadPoster(img, img.tag as? String)
        }
    }

    /** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
    /** At least [wide] px across (halved while it stays that wide); [config] RGB_565 for small cards, ARGB for large art. */
    internal fun decode(uri: String, wide: Int = dp(220), config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap? {
        val bytes = openStream(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= wide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun openStream(uri: String): InputStream =
        if (uri.startsWith("http")) {
            ImageCache.open(this, uri)
        } else {
            contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
        }

    // ---------- shared view bits ----------

    /**
     * tvOS-style focus, no outline: the focused card lifts (scale + shadow). Light mode adds a soft
     * top sheen; dark mode brightens the glass gradient instead (a white sheen would haze it grey).
     * Only transform/alpha/colour change, so it stays on the GPU and costs no re-layout.
     */
    internal fun style(v: View, radius: Int, card: Boolean, lift: Float = 1.08f, frost: Boolean = true, rise: Boolean = true, sheen: Boolean = true, onFocus: ((Boolean) -> Unit)? = null) {
        fun gradient(colors: IntArray) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = radius.toFloat()
            setDither(true)
        }
        val sheenColors = intArrayOf(0x40FFFFFF, 0x00FFFFFF)
        val sheenLayer = Baked("sheen $radius", gradient(sheenColors)).apply { alpha = 0 }
        val rest = if (card) Baked("card $radius ${cardRest.contentToString()}", gradient(cardRest)) else null
        val lit = if (card) Baked("card $radius ${cardLit.contentToString()}", gradient(cardLit)) else null
        val frosted = frost && card && glassActive()
        if (frosted) glass.add(v, radius.toFloat(), sheen)
        v.background = if (dark && card) rimCard(radius, false, frosted = frosted) else rest ?: GradientDrawable().apply { cornerRadius = radius.toFloat() }
        if (!dark && sheen) v.foreground = sheenLayer
        v.clipToOutline = true
        v.elevation = if (card) dp(2).toFloat() else 0f
        v.isFocusable = true
        v.isClickable = true
        v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
        v.setOnFocusChangeListener { _, focused ->
            sheenLayer.alpha = if (focused) 255 else 0
            if (card && dark) v.background = rimCard(radius, focused, frosted = frosted)
            else if (card) v.background = if (focused) lit else rest
            if (frosted) glass.kick(260) // the lift moves the tile: its blur follows
            // The selected tile rises out of the row's line. Its growth stops short of the gap, so at the
            // largest size it still doesn't cover its neighbours.
            val grow = if (focused && v.width > 0) minOf(lift, 1 + dp(GRID_GAP) * 1.2f / v.width) else 1f
            v.animate()
                .scaleX(grow).scaleY(grow)
                .translationY(if (focused && rise) -maxOf(v.height * 0.06f, dp(4).toFloat()) else 0f)
                .translationZ(if (focused) dp(14).toFloat() else 0f)
                .setDuration(160).setInterpolator(DECELERATE).start()
            onFocus?.invoke(focused)
        }
    }

    /**
     * A dark-mode card on true black: near-black glass with a hairline rim, brighter along the top edge as if
     * lit from above. [lit] is the focused look: brighter glass and rim. [solid]: no see-through, for menus and
     * dialogs, which sit over other things and have to stay readable (light mode's are solid too).
     */
    internal fun rimCard(radius: Int, lit: Boolean, solid: Boolean = false, frosted: Boolean = false): Drawable {
        val style = if (frosted) glassLook().active ?: GlassStyle.FROSTED else null
        val glass = (if (lit) DARK_CARD_LIT else DARK_CARD).let { c ->
            if (solid) IntArray(c.size) { c[it] or 0xFF000000.toInt() }
            else if (style != null) IntArray(c.size) { glassLook().tile(lit) }
            else c
        }
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, glass).apply {
            cornerRadius = radius.toFloat()
            if (style == null) setStroke(1, if (lit) RIM_SIDE_LIT else RIM_SIDE) // 1px, not 1dp: a hairline stays crisp
            setDither(true) // near black the gradient has ~8 steps; dithered, they don't show as bands
        }
        // Glass has no outline (its sheen and shadow give its shape, see GlassLayer); a solid card keeps its rim.
        if (style != null) return Baked("rim $radius $lit $solid $frosted", body)
        return Baked("rim $radius $lit $solid $frosted", LayerDrawable(arrayOf(body, ColorDrawable(if (lit) RIM_TOP_LIT else RIM_TOP))).apply {
            setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
            setLayerHeight(1, 1)
            setLayerInsetLeft(1, radius)
            setLayerInsetRight(1, radius)
        })
    }

    /**
     * Draws [src] once into a bitmap, then only copies that bitmap. Drawn in software, so the dithering is real
     * and free afterwards; dithered on the GPU it costs every pixel on every frame, which tripled app-row jank on
     * the TV. Same look and size share one bitmap, so a row of tiles costs one.
     */
    private inner class Baked(private val key: String, private val src: Drawable) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var bmp: Bitmap? = null
        override fun onBoundsChange(b: Rect) {
            src.setBounds(0, 0, b.width(), b.height())
            bmp = if (b.isEmpty) null else baked.getOrPut("$key ${b.width()}x${b.height()}") {
                Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also { src.draw(Canvas(it)) }
            }
        }
        override fun draw(c: Canvas) { bmp?.let { c.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), paint) } }
        override fun getOutline(o: Outline) { src.getOutline(o); o.offset(bounds.left, bounds.top) }
        override fun setAlpha(a: Int) { if (paint.alpha != a) { paint.alpha = a; invalidateSelf() } }
        override fun getAlpha() = paint.alpha
        override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
        // A tinted tile (Banner style, an app with no banner) is one solid colour in the card's shape.
        override fun setTintList(t: ColorStateList?) { paint.colorFilter = t?.let { PorterDuffColorFilter(it.defaultColor, PorterDuff.Mode.SRC_IN) }; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    // ponytail: never evicted; fine while only a few tile sizes exist, cap it if views start animating their size
    private val baked = HashMap<String, Bitmap>()

    /** Supporting text: the secondary grey in dark mode, [lightAlpha] of the main colour in light mode. */
    internal fun TextView.secondary(lightAlpha: Float) = if (dark) setTextColor(text2()) else alpha = lightAlpha

    /** A name that stays inside its box: it scrolls slowly while its tile is selected if it is too wide (see FittingLabel). */
    private fun fittingLabel(text: String, size: Float) = FittingLabel(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    internal fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    internal companion object {
        var fakeDevice: String? = null // debug builds: see fakeSetup()
        var fakeSdk = 0
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        const val INSTALL_REQUEST = 4
        val ROW_ALIGN = linkedMapOf( // setting -> its name, and the row's gravity in the scroller
            "left" to (R.string.align_left to Gravity.START),
            "centre" to (R.string.align_centre to Gravity.CENTER_HORIZONTAL),
            "right" to (R.string.align_right to Gravity.END),
        )
        const val SCRIM_ALPHA = 0x99 // the settings panel's dim over the home screen
        const val AREA_ALL = 0
        const val AREA_CLOCK = 1
        const val AREA_APPS = 2
        const val AREA_SHOWS = 3
        /** The part of the home screen each section changes, lit by the spotlight; other sections dim it all. */
        val SECTION_AREAS = mapOf(
            R.string.section_style to AREA_ALL, R.string.section_clock_weather to AREA_CLOCK,
            R.string.section_app_row to AREA_APPS, R.string.bottom_row to AREA_SHOWS,
        )
        /** Main-list key of the wallpaper card; the keys that open the wallpaper page. */
        const val WALLPAPER_CARD = -2
        val WALLPAPER_KEYS = setOf(R.string.wallpaper, R.string.folder_photo_changes, R.string.wp_sources)
        /** Rows whose choices are drawn as pictures (see OptionArt). */
        val ROW_ART = mapOf(R.string.row_size to "size", R.string.row_position to "position")
        val ROW_ICONS = mapOf(
            R.string.theme to R.drawable.ms_contrast, R.string.glass to R.drawable.ms_blur_on, R.string.app_animations to R.drawable.ms_open_in_full,
            R.string.edit_apps_row to R.drawable.ms_swap_horiz, R.string.apps_in_row to R.drawable.ms_checklist, R.string.sort_apps to R.drawable.ms_sort, R.string.fav_sort to R.drawable.ms_sort,
            R.string.icon_style to R.drawable.ms_apps, R.string.row_size to R.drawable.ms_format_size, R.string.app_names to R.drawable.ms_label,
            R.string.row_position to R.drawable.ms_format_align_left,
            R.string.show_row to R.drawable.ms_visibility, R.string.apps_when_hidden to R.drawable.ms_vertical_align_bottom, R.string.row_name to R.drawable.ms_edit,
            R.string.show_icons to R.drawable.ms_badge, R.string.cw_apps to R.drawable.ms_widgets, R.string.cw_channels to R.drawable.ms_podcasts,
            R.string.restore_hidden to R.drawable.ms_visibility_off,
            R.string.clock_style to R.drawable.ms_schedule, R.string.clock_size to R.drawable.ms_format_size,
            R.string.date_format to R.drawable.ms_schedule, R.string.time_format to R.drawable.ms_schedule,
            R.string.weather to R.drawable.ms_partly_cloudy_day, R.string.weather_units to R.drawable.ms_thermostat, R.string.weather_location to R.drawable.ms_location_on,
            R.string.tmdb_row to R.drawable.ms_movie, R.string.tmdb_mode to R.drawable.ms_movie, R.string.tmdb_region to R.drawable.ms_location_on, R.string.tmdb_refresh to R.drawable.ms_shuffle, R.string.wn_toggle to R.drawable.ms_movie, R.string.wn_service to R.drawable.ms_movie, R.string.wn_account to R.drawable.ms_movie, R.string.tmdb_services to R.drawable.ms_movie,
            R.string.screensaver to R.drawable.ms_bedtime, R.string.test_screensaver to R.drawable.ms_play_circle, R.string.screensaver_timing to R.drawable.ms_timer,
            R.string.home_button to R.drawable.ms_home, R.string.default_launcher to R.drawable.ms_home_app_logo, R.string.smooth_volume to R.drawable.ms_volume_up,
            R.string.recent_previews to R.drawable.ms_view_carousel,
            R.string.section_keys to R.drawable.ms_keyboard, R.string.setup_home to R.drawable.ms_restart_alt,
        )
        val SECTION_ICONS = mapOf(
            R.string.section_style to R.drawable.ms_palette, R.string.section_clock_weather to R.drawable.ms_partly_cloudy_day,
            R.string.section_app_row to R.drawable.ms_view_carousel, R.string.bottom_row to R.drawable.ms_video_library,
            R.string.section_tmdb to R.drawable.ms_movie,
            R.string.section_screensaver to R.drawable.ms_bedtime, R.string.section_remote_home to R.drawable.ms_settings_remote,
        )
        /** The help line shown at the bottom of Nooko Launcher settings while a row is selected. */
        val ROW_HELP = mapOf(
            R.string.section_style to R.string.help_style, R.string.section_clock_weather to R.string.help_clock_weather,
            R.string.theme to R.string.help_theme, R.string.glass to R.string.help_glass, R.string.app_animations to R.string.help_app_animations,
            R.string.app_names to R.string.help_app_names, R.string.row_position to R.string.help_row_position, R.string.show_row to R.string.help_show_row,
            R.string.clock_style to R.string.help_clock_style, R.string.clock_size to R.string.help_clock_size, R.string.weather to R.string.help_weather, R.string.weather_units to R.string.help_units, R.string.screensaver to R.string.help_screensaver_pick,
            R.string.test_screensaver to R.string.help_preview, R.string.folder_photo_changes to R.string.help_photo_every,
            R.string.section_app_row to R.string.help_apps_row, R.string.bottom_row to R.string.help_shows_row,
            R.string.section_tmdb to R.string.help_tmdb, R.string.tmdb_row to R.string.help_tmdb, R.string.tmdb_mode to R.string.help_tmdb_mode, R.string.tmdb_region to R.string.help_tmdb_region, R.string.tmdb_refresh to R.string.help_tmdb_refresh, R.string.wn_toggle to R.string.help_wn_toggle, R.string.wn_service to R.string.help_wn_service, R.string.wn_account to R.string.help_wn_account, R.string.tmdb_services to R.string.help_tmdb_services,
            R.string.section_screensaver to R.string.help_screensaver, R.string.section_remote_home to R.string.help_remote_home,
            R.string.section_lock to R.string.help_lock,
            R.string.weather_location to R.string.help_location,
            R.string.edit_apps_row to R.string.help_rearrange, R.string.apps_in_row to R.string.help_choose_apps,
            R.string.sort_apps to R.string.help_order, R.string.fav_sort to R.string.help_fav_sort, R.string.icon_style to R.string.help_icon_style, R.string.row_size to R.string.help_row_size,
            R.string.apps_when_hidden to R.string.help_apps_when_hidden, R.string.row_name to R.string.help_row_name,
            R.string.show_icons to R.string.help_show_icons,
            R.string.cw_apps to R.string.help_from_apps, R.string.cw_channels to R.string.help_channels, R.string.cards_rows to R.string.help_cards_rows,
            R.string.wp_sources to R.string.help_wp_sources, R.string.restore_hidden to R.string.help_hidden_shows, R.string.screensaver_timing to R.string.help_screensaver_delay,
            R.string.home_button to R.string.help_home_button, R.string.smooth_volume to R.string.help_smooth_volume,
            R.string.recent_previews to R.string.help_recent_previews,
            R.string.default_launcher to R.string.help_default_launcher,
            R.string.setup_home to R.string.help_run_setup, R.string.section_keys to R.string.help_remote_keys,
        )
        val ROW_SHOW = linkedMapOf("always" to R.string.always, "down" to R.string.on_down, "never" to R.string.never) // "Show row"
        val CLOCK_SIZES = linkedMapOf(38 to R.string.clock_small, 48 to R.string.clock_medium, 60 to R.string.clock_large) // sp
        val CLOCK_STYLES = linkedMapOf("light" to R.string.clock_light, "bold" to R.string.clock_bold, "serif" to R.string.clock_serif)
        val CARDS_ROWS = linkedMapOf(ShowRows.MIXED to R.string.cards_mixed, ShowRows.BY_TITLE to R.string.cards_title, ShowRows.PER_CHANNEL to R.string.cards_each) // "Rows"
        const val BADGE = 0xFFFF453A.toInt()
        // Dark mode tokens, for AMOLED: true black, and near-black cards that read as cards because their edge
        // catches light (brightest along the top), the way light-mode cards use a soft shadow.
        const val DARK_BG = 0xFF000000.toInt() // home ground, settings panel, inset boxes
        const val DARK_SURFACE = 0xFF0B0B0B.toInt() // placeholders behind images
        const val DARK_TEXT = 0xFFF5F5F5.toInt()
        const val DARK_TEXT_2 = 0xFF7A7A7A.toInt() // values, unfocused rows, supporting text
        // Translucent (70% at rest, 80% selected), so the wallpaper shows through the tiles like frosted glass.
        /** Android's own Watch Next list, listed with the channels so it can be turned off too. */
        const val WATCH_NEXT = "watchnext"
        val DARK_CARD = intArrayOf(0xE60B0B0B.toInt(), 0xE6040404.toInt())
        val DARK_CARD_LIT = intArrayOf(0xF2141414.toInt(), 0xF2070707.toInt())
        const val RIM_TOP = 0x17FFFFFF // white 9%
        const val RIM_SIDE = 0x0AFFFFFF // white 4%
        const val RIM_TOP_LIT = 0x38FFFFFF // white 22%
        const val RIM_SIDE_LIT = 0x17FFFFFF
        const val PANEL_EDGE = 0x0FFFFFFF // white 6%, the settings panel's left edge
        const val HELP_URL = "https://github.com/depo23/Nooko-Launcher#use-nooko-as-default-home"
        const val KOFI_URL = "https://ko-fi.com/zenmode"
        const val TOGGLE_ON = "\u0000on" // row values drawn as a switch instead of text
        const val TOGGLE_OFF = "\u0000off"
        const val BANNER_DIM = 0.3f // black laid over a colour banner at rest, so the selected one stands out
        const val FETCH_OK = 0
        const val FETCH_DEAD = 1
        const val FETCH_NETWORK = 2
        const val SHOW_MAX = 12_000_000 // bytes: a bigger show picture is left out
        const val PIN_TRIES = 5 // wrong PINs before a 30 s wait
        const val UNLOCKED_MS = 5 * 60_000L
        const val MEDIA_REQUEST = 3
        const val PER_APP = 3 // Continue Watching items per app ahead of the rest
        const val ROW_CARDS = 20 // the most cards a row holds
        const val SWAP_OUT_MS = 90L // another source with fewer shows: the extra cards fade out
        const val POSTER_FADE_MS = 220L // a poster that lands late fades in on its card
        const val TITLE_SLIDE_MS = 260L // a source change: the title slides first ...
        const val CARD_SLIDE_MS = 320L // ... then each card's content, about the time to see it move
        const val SLIDE_OUT_MS = 110L // the old poster leaves in this part of CARD_SLIDE_MS
        const val STAGGER_MS = 30L // each card starts this long after the one before it
        const val SLIDE = 24 // dp the content moves
        const val CHEVRON = 16 // dp, the arrows beside the shows row's title
        const val FADING = "fading" // the tag of a card that is fading out
        const val HERO_SCROLL_MS = 15_000L // the TMDB row moves on by one card this often, unless a card is selected
        const val HERO_TTL_MS = 6 * 60 * 60 * 1000L // TMDB trending is refetched after this long
        const val CARDS_AHEAD = 8 // Continue Watching cards built past the focused one (see addCardsTo)
        val READ_PHOTOS = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        /** Minutes between folder photos. */
        val PHOTO_EVERY = linkedMapOf(
            1 to R.string.every_1, 5 to R.string.every_5, 10 to R.string.every_10, 15 to R.string.every_15, 30 to R.string.every_30,
            60 to R.string.every_60, 1440 to R.string.every_1440, 10080 to R.string.wall_every_week, 43200 to R.string.wall_every_30_days,
        )
        const val CHOSEN = "✓"
        const val REPO = "depo23/Nooko-Launcher"
        const val UPDATE_STATUS = "dev.nooko.launcher.UPDATE_STATUS"
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val ROW_TILES = 8 // across at Standard size; the other sizes scale from it
        /** "Size" setting -> its name, and how many tiles fit across. 8 and 4 keep the columns over the four cards. */
        val ROW_SIZES = linkedMapOf("standard" to (R.string.size_standard to 8), "large" to (R.string.size_large to 6), "tv" to (R.string.size_tv to 5), "largest" to (R.string.size_largest to 4))
        /** Icon style setting -> its name. */
        val ICON_STYLES = linkedMapOf(ICON_STYLE_KEYS[0] to R.string.monochrome, ICON_STYLE_KEYS[1] to R.string.colour, ICON_STYLE_KEYS[2] to R.string.banner, ICON_STYLE_KEYS[3] to R.string.banner_mono)
        /** Glass setting -> its styles in cycling order; null is off. */
        val GLASS_STYLES: List<GlassStyle?> = listOf(null, GlassStyle.FROSTED, GlassStyle.CLEAR)
        val THEMES = listOf("light", "dark", "wallpaper", "auto")
        const val GRID_GAP = 16 // dp; shared by the apps row and the cards so their columns line up
        const val INK_MARK = "ink mark" // tag on a card's detail line whose source mark takes the text colour
        /** Shown names for the wallpaper keys saved in prefs (the keys stay English). */
        val PIN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val GREYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val DECELERATE = android.view.animation.DecelerateInterpolator(1.5f)
        const val SIDE_WINDOW = 0.78f // Recent apps: scale of the windows beside the selected one
        val EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f) // Material's emphasized decelerate
        @Volatile var APPS: List<App>? = null
        @Volatile var SOURCES: List<App>? = null
        var CVTE_SOURCE_PANEL: Intent? = null
        /** Mono marks made by glyph(), by package; null = icon has no usable symbol. Main thread only. */
        val GLYPHS = HashMap<String, Bitmap?>()
        /** accent() colours by package; null = no clear colour. Main thread only. */
        val ACCENTS = HashMap<String, Int?>()

        /**
         * Colour mode tints for the bundled marks: Simple Icons' brand hex, except where that's too dark to
         * read on dark cards (PlayStation, Prime Video) or grey (Google Play), where the brand's brighter blue/green is used.
         */
        val BRAND_COLOURS = mapOf(
            "playstation" to 0xFF0070D1.toInt(),
            "xbox" to 0xFF107C10.toInt(),
            "nintendoswitch" to 0xFFE60012.toInt(),
            "com.netflix.ninja" to 0xFFE50914.toInt(),
            "com.stremio.one" to 0xFF8D3DAF.toInt(),
            "io.gh.reisxd.tizentube.cobalt" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tv" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tvmusic" to 0xFFFF0000.toInt(),
            "com.amazon.amazonvideo.livingroom" to 0xFF00A8E1.toInt(),
            "com.spotify.tv.android" to 0xFF1DB954.toInt(),
            "org.videolan.vlc" to 0xFFFF8800.toInt(),
            "com.android.vending" to 0xFF01875F.toInt(),
        )

        val DEFAULT_ORDER = listOf(
            "com.netflix.ninja",
            "com.stremio.one",
            "io.gh.reisxd.tizentube.cobalt",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
        )

        /** HDMI tile icons by hdmiIcon key: brand marks (Simple Icons, CC0), the rest from Material Symbols (Apache 2.0). */
        val HDMI_ICONS = mapOf(
            "playstation" to R.drawable.logo_playstation,
            "xbox" to R.drawable.logo_xbox,
            "nintendoswitch" to R.drawable.logo_nintendoswitch,
            "appletv" to R.drawable.logo_appletv,
            "nvidia" to R.drawable.logo_nvidia,
            "chromecast" to R.drawable.logo_chromecast,
            "settop" to R.drawable.ic_settop,
            "playback" to R.drawable.ic_playback,
            "hdmi" to R.drawable.ic_hdmi,
        )

        /** Monochrome brand marks (Simple Icons, CC0). `true` = wordmark, shown without a label. */
        val LOGOS = mapOf(
            "com.netflix.ninja" to (R.drawable.logo_netflix to false),
            "com.stremio.one" to (R.drawable.logo_stremio to false),
            "io.gh.reisxd.tizentube.cobalt" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tv" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tvmusic" to (R.drawable.logo_youtubemusic to false),
            "com.amazon.amazonvideo.livingroom" to (R.drawable.logo_primevideo to true),
            "com.spotify.tv.android" to (R.drawable.logo_spotify to false),
            "org.videolan.vlc" to (R.drawable.logo_vlcmediaplayer to false),
            "com.android.vending" to (R.drawable.logo_googleplay to false),
        )
    }
}

/** White or near-black over a background of luminance [lum], whichever has the higher contrast (WCAG). */
/**
 * White or near-black text over [frames] (the luminances behind the text, one array per frame). Each colour is judged
 * by its worst case: white by the bright end (80th percentile) of the brightest frame, dark by the dim end (20th) of
 * the darkest, so one white thread or one bright scene counts, not the average. White wins unless dark reads clearly
 * better (1.5x), as white with its shadow holds up best on busy pictures.
 */
internal fun inkFor(frames: List<FloatArray>): Int {
    val dark = 0xFF111214.toInt()
    val darkLum = 0.0063f // luminance of #111214
    val ends = frames.filter { it.isNotEmpty() }.map { f -> f.sorted().let { it[it.size / 5] to it[(it.size - 1) * 4 / 5] } }
    if (ends.isEmpty()) return Color.WHITE
    val white = 1.05f / (ends.maxOf { it.second } + 0.05f)
    val black = (ends.minOf { it.first } + 0.05f) / (darkLum + 0.05f)
    return if (black > white * 1.5f) dark else Color.WHITE
}


/** The words on the pages the TV serves to a phone (the TMDB key form, the photo upload), by their key in those pages. */
private val PHONE_TEXT = mapOf(
    "key_title" to R.string.phone_key_title, "key_copy" to R.string.phone_key_copy, "key_link" to R.string.phone_key_link,
    "key_label" to R.string.tmdb_key, "send" to R.string.phone_send, "wifi" to R.string.phone_wifi_note,
    "key_bad_title" to R.string.phone_key_bad_title, "key_bad" to R.string.phone_key_bad, "back_form" to R.string.phone_back_form,
    "key_sent_title" to R.string.phone_key_sent_title, "key_sent" to R.string.phone_key_sent, "done" to R.string.done,
    "code_gone_title" to R.string.phone_code_gone_title, "scan_tv" to R.string.phone_scan_tv,
    "up_title_wall" to R.string.phone_up_title_wall, "up_title_pic" to R.string.phone_up_title_pic,
    "up_h_wall" to R.string.phone_up_h_wall, "up_h_pic" to R.string.change_app_picture,
    "up_p_wall" to R.string.phone_up_p_wall, "up_p_pic" to R.string.phone_up_p_pic,
    "choose_pic" to R.string.phone_choose_pic, "choose_photo" to R.string.phone_choose_photo, "upload" to R.string.phone_upload,
    "uploading" to R.string.phone_uploading, "send_another" to R.string.phone_send_another, "unreachable" to R.string.phone_unreachable,
    "not_found" to R.string.phone_not_found, "not_video" to R.string.phone_not_video, "too_big" to R.string.phone_too_big,
    "added" to R.string.phone_added, "bad_video" to R.string.phone_bad_video, "bad_photo" to R.string.phone_bad_photo,
    "not_allowed" to R.string.phone_not_allowed,
)

internal fun MainActivity.phoneText(): PhoneText = { key, english ->
    when (key) {
        "lang" -> resources.configuration.locales[0].language
        "dir" -> if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        else -> PHONE_TEXT[key]?.let { getString(it) } ?: english
    }
}
