package dev.nooko.launcher

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Version 1 transfers portable preferences only. Missing supported keys return to Nooko Launcher defaults. */
internal object SettingsTransfer {
    private const val LIMIT = 65_536
    private val choices = mapOf(
        "theme" to setOf("light", "dark", "wallpaper", "auto"),
        "glass_style" to setOf("off", "frosted", "clear"),
        "icon_style" to setOf("mono", "colour", "banner", "bannermono"),
        "row_size" to setOf("standard", "large", "tv", "largest"),
        "row_align" to setOf("left", "centre", "right"),
        "names" to setOf("focus", "always", "never"),
        "row_show" to setOf("always", "down", "never"),
        "sort" to setOf("manual", "usage"),
        "fav_sort" to setOf("custom", "usage"),
        "tmdb_mode" to setOf("cards", "full"),
        "wn_provider" to setOf("trakt", "simkl", "mdblist", "tmdb"),
        "ss_folder" to setOf("tmdb"), // a photo folder on this TV does not travel
        "clock_size" to setOf("38", "48", "60"),
        "clock_style" to setOf("light", "bold", "serif"),
        "weather" to setOf("off", "c", "f"),
        "wx_unit" to setOf("c", "f"),
    )
    private val switches = setOf("glass", "colour_icons", "app_anim", "row_drop", "cw_colour", "geo_manual", "grid_v1", "look_v2", "wn_on",
        "smooth_volume", "video_keep_one", "backup_tmdb")
    private val clockFormats = ClockFormat.entries.associateBy { it.key }
    private val keys = choices.keys + switches + clockFormats.keys + setOf("order", "row_hidden", "cw_off", "row_name", "top_bar",
        "wp_every", "geo_city", "geo_lat", "geo_lon", "home_hidden", "reddit_subs", "wp_light", "wp_dark", "tmdb_key", "tmdb_region", "tmdb_svc_ids",
        "cards_rows", "cards_src", "keymap", "glass_hue", "ch_flip", "hidden_watch",
        "wn_secrets")
    private val region = Regex("[A-Z]{2}")
    private val tmdbKey = Regex("[a-fA-F0-9]{32}")
    private val subreddit = Regex("[A-Za-z0-9_]{2,30}")
    private val wallpaper = Regex("bing|reddit|plain:[A-Za-z ]{2,20}")
    private val pkg = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val word = Regex("[a-z_]{1,24}")

    @Suppress("DEPRECATION")
    const val BACKUP_NAME = "nooko-launcher.bak"

    /** Where the backup is shown to the person. */
    fun where(context: Context): String = if (Build.VERSION.SDK_INT >= 29) "Download/$BACKUP_NAME" else file(context).path

    /** The backup Nooko Launcher's predecessor (ATV Launcher) saved; it can be loaded here too. */
    private const val OLD_BACKUP_NAME = "atv-launcher.bak"

    private fun shared(context: Context, name: String = BACKUP_NAME): Uri? = context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(name, "Download%"), null)
        ?.use { if (it.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0)) else null }

    /**
     * The backup goes to the Download folder as nooko-launcher.bak: any file manager and adb can reach it, and it stays when
     * Nooko Launcher is uninstalled (the app's own folder does not). Before Android 10 it stays in the app's folder.
     */
    fun saveShared(context: Context, all: Map<String, *>): String {
        if (Build.VERSION.SDK_INT < 29) return file(context).also { save(it, all) }.path
        val bytes = encode(all)
        val resolver = context.contentResolver
        fun create() = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }) ?: throw IOException("Cannot create the backup file")
        // An earlier backup is written over; one an earlier install made can be read-only to this one, so a new file is made.
        val stream = shared(context)?.let { try { resolver.openOutputStream(it, "wt") } catch (e: Exception) { null } }
            ?: resolver.openOutputStream(create(), "wt") ?: throw IOException("Cannot write the backup file")
        stream.use { it.write(bytes) }
        return where(context)
    }

    fun loadShared(context: Context): Map<String, Any> {
        // This app's own backup first, then ATV Launcher's settings straight from ATV Launcher, if it is installed (Android lets no
        // other app read the file it saved). Same signing key only.
        if (Build.VERSION.SDK_INT >= 29) shared(context)?.let { uri ->
            try { context.contentResolver.openInputStream(uri)?.use { input -> return decode(input.readBytes()) } } catch (_: Exception) { }
        }
        fromAtvLauncher(context)?.let { return it }
        // The backup service of Android 10 and newer, when this app made the file or the system lets it see it.
        if (Build.VERSION.SDK_INT >= 29) for (name in listOf(BACKUP_NAME, OLD_BACKUP_NAME)) shared(context, name)?.let { uri ->
            try { context.contentResolver.openInputStream(uri)?.use { input -> return decode(input.readBytes()) } } catch (_: Exception) { }
        }
        // Then the files themselves: this app's backup, ATV Launcher's backup in Download, and the one ATV Launcher kept in its
        // own media folder before Android 10 (it stays readable while ATV Launcher is installed).
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        val found = listOfNotNull(
            File(root, "Download/$BACKUP_NAME"), File(root, "Download/$OLD_BACKUP_NAME"),
            File(root, "Android/media/dev.atvlauncher/backup/atv-settings.json"),
            runCatching { file(context) }.getOrNull(),
        )
        var failure: Exception = IOException("No backup found")
        for (candidate in found) {
            try { if (candidate.exists()) return if (candidate.name.endsWith(".bak")) decode(candidate.readBytes()) else load(candidate) }
            catch (e: Exception) { failure = e }
        }
        throw failure
    }

    /** The settings ATV Launcher hands over, or null when it is not installed, not updated yet or not signed with our key. */
    private fun fromAtvLauncher(context: Context): Map<String, Any>? = try {
        context.contentResolver.query(Uri.parse("content://dev.atvlauncher.backup/settings"), null, null, null, null)?.use {
            if (it.moveToFirst()) decode(it.getBlob(0)) else null
        }
    } catch (_: Exception) { null }

    fun file(context: Context): File = File(context.externalMediaDirs.firstOrNull()
        ?: throw IOException("External media storage is unavailable"), "backup/atv-settings.json")

    fun portable(all: Map<String, *>): Map<String, Any> {
        val values = all.filterKeys { it in keys }.toMutableMap()
        // HDMI identifiers and automatic location results belong to this TV.
        (values["order"] as? String)?.let { order -> values["order"] = order.split(',').filter { pkg.matches(it) }.joinToString(",") }
        for (key in listOf("row_hidden", "home_hidden")) (values[key] as? Set<*>)?.let { hidden -> values[key] = hidden.filterIsInstance<String>().filter { pkg.matches(it) }.toSet() }
        // A picture or folder on this TV does not travel: only the built-in sources do.
        for (key in listOf("wp_light", "wp_dark")) if (values[key] is String && !wallpaper.matches(values[key] as String)) values.remove(key)
        if (values["geo_manual"] != true) for (key in listOf("geo_city", "geo_lat", "geo_lon")) values.remove(key)
        // A value that does not fit (an old or unusual setting) is left out; it must not stop the rest being saved.
        for (key in values.keys.toList()) if (key != "geo_city" && key != "geo_lat" && key != "geo_lon" &&
            runCatching { validate(mapOf(key to values[key])) }.isFailure) values.remove(key)
        return validate(values)
    }

    /** Also used before any preference mutation; JSON numbers are converted to their declared preference types. */
    fun validate(values: Map<String, *>): Map<String, Any> = LinkedHashMap<String, Any>().apply {
        for ((key, value) in values) {
            if (key !in SettingsTransfer.keys) continue
            val checked: Any = when {
                key in choices -> {
                    require(value is String && value in choices.getValue(key)) { "Unsupported $key" }
                    value
                }
                key in switches -> { require(value is Boolean) { "Invalid $key" }; value }
                key in clockFormats -> {
                    require(value is String && clockFormats.getValue(key).accepts(value)) { "Invalid $key" }
                    value
                }
                key == "geo_lat" || key == "geo_lon" -> {
                    require(value is Number) { "Invalid $key" }
                    val number = value.toDouble()
                    val max = if (key == "geo_lat") 90.0 else 180.0
                    require(number.isFinite() && number in -max..max) { "Invalid $key" }
                    number.toFloat()
                }
                key == "wp_every" -> {
                    require(value is Number && value.toDouble() in setOf(1.0, 5.0, 10.0, 15.0, 30.0, 60.0, 1440.0, 10080.0, 43200.0)) { "Invalid $key" }
                    value.toInt()
                }
                key == "glass_hue" -> {
                    require(value is Number && value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid $key" }
                    value.toInt()
                }
                key == "ch_flip" || key == "hidden_watch" -> {
                    require(value is Collection<*> && value.size <= 512 && value.all { it is String && it.length <= 200 && '\u0000' !in it }) { "Invalid $key" }
                    value.filterIsInstance<String>().toSet()
                }
                key == "cards_rows" -> {
                    require(value is String && word.matches(value)) { "Invalid $key" }
                    value
                }
                key == "cards_src" -> {
                    require(value is String && value.length <= 200 && '\u0000' !in value) { "Invalid $key" }
                    value
                }
                key == "keymap" -> {
                    require(value is String && value.length <= 4096 && '\u0000' !in value) { "Invalid $key" }
                    value
                }
                key == "wn_secrets" -> {
                    require(value is String && value.length <= 12_000 && value.startsWith("{") && value.endsWith("}")) { "Invalid $key" }
                    value
                }
                key == "tmdb_key" -> {
                    require(value is String && tmdbKey.matches(value)) { "Invalid $key" }
                    value
                }
                key == "tmdb_region" -> {
                    require(value is String && region.matches(value)) { "Invalid $key" }
                    value
                }
                key == "tmdb_svc_ids" -> {
                    require(value is Collection<*> && value.size <= 200 && value.all { it is String && it.length in 1..8 && it.all(Char::isDigit) }) { "Invalid $key" }
                    value.filterIsInstance<String>().toSet()
                }
                key == "reddit_subs" -> {
                    require(value is Collection<*> && value.size in 1..32 && value.all { it is String && subreddit.matches(it) }) { "Invalid $key" }
                    value.filterIsInstance<String>().toSet()
                }
                key == "wp_light" || key == "wp_dark" -> {
                    require(value is String && wallpaper.matches(value)) { "Invalid $key" }
                    value
                }
                key == "row_hidden" || key == "cw_off" || key == "home_hidden" -> {
                    require(value is Collection<*> && value.size <= 512 && value.all { it is String && pkg.matches(it) }) { "Invalid $key" }
                    value.filterIsInstance<String>().toSet()
                }
                else -> {
                    require(value is String && value.length <= 16_384 && '\u0000' !in value) { "Invalid $key" }
                    if (key == "order") require(value.isEmpty() || value.split(',').let { it.size <= 512 && it.all(pkg::matches) }) { "Invalid order" }
                    if (key == "top_bar") require(value.split(',').all { it in setOf("wifi", "sources", "apps", "system", "atv", "heart", "power", "recents", "screensaver") }) { "Invalid top bar" }
                    value
                }
            }
            put(key, checked)
        }
        if (get("geo_manual") == true) require(listOf("geo_city", "geo_lat", "geo_lon").all { containsKey(it) }) { "Incomplete weather location" }
        else for (key in listOf("geo_city", "geo_lat", "geo_lon")) remove(key)
    }

    fun encode(all: Map<String, *>): ByteArray {
        val settings = JSONObject()
        portable(all).forEach { (key, value) -> settings.put(key, if (value is Set<*>) JSONArray(value.toList()) else value) }
        return JSONObject().put("format", "atv-settings").put("version", 1).put("settings", settings)
            .toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= LIMIT) { "Settings file is too large" } }
    }

    fun decode(bytes: ByteArray): Map<String, Any> {
        require(bytes.size <= LIMIT) { "Settings file is too large" }
        val text = bytes.toString(Charsets.UTF_8)
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) { "Settings file is too deeply nested" } }
                '}', ']' -> depth--
            }
        }
        val parser = JSONTokener(text)
        val root = parser.nextValue()
        require(root is JSONObject && parser.nextClean() == '\u0000') { "Invalid settings file" }
        require(root.opt("format") == "atv-settings" && root.opt("version") == 1) { "Unsupported settings file" }
        val settings = root.getJSONObject("settings")
        val values = LinkedHashMap<String, Any?>().apply {
            for (key in settings.keys()) {
                val value = settings.get(key)
                put(key, if (value is JSONArray) (0 until value.length()).map { value.get(it) } else value)
            }
        }
        return validate(values)
    }

    fun save(file: File, all: Map<String, *>) {
        val bytes = encode(all)
        if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw IOException("Cannot create backup folder")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun load(file: File): Map<String, Any> = AtomicFile(file).openRead().use { input ->
        val bytes = ByteArray(LIMIT + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        decode(bytes.copyOf(size))
    }

    fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val valid = validate(values)
        val before = prefs.all.filterKeys { it in keys || it == "geo_time" || it == "wx_time" }
        fun write(values: Map<String, *>, restored: Boolean = true): Boolean {
            val edit = prefs.edit()
            (keys + setOf("geo_time", "wx_time")).forEach { edit.remove(it) }
            for ((key, value) in values) when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            // What was restored is the layout and the look: the first-run move to the defaults must not run over it.
            if (restored && values.isNotEmpty()) edit.putBoolean("look_v2", true).putBoolean("grid_v1", true)
            return edit.commit()
        }
        if (!write(valid)) {
            val restored = write(before, restored = false)
            throw IOException(if (restored) "Cannot save settings" else "Cannot save or restore settings")
        }
    }
}
