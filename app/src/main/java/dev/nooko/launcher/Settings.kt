package dev.nooko.launcher

import android.content.SharedPreferences

/**
 * The settings of the TMDB row, Watch Next, the shelf order and the backup, with a name and a type each. The key of every one is
 * written here once, instead of as a string at each place that reads it. (The other settings still use string keys.)
 */
internal class Settings(private val prefs: SharedPreferences) {
    /** Fullscreen (true) or cards (false) for the TMDB titles. */
    var tmdbFull: Boolean
        get() = prefs.getString("tmdb_mode", "cards") == "full"
        set(v) = prefs.edit().putString("tmdb_mode", if (v) "full" else "cards").apply()

    /** The country whose streaming services are shown; null means the TV's own. */
    var tmdbRegion: String?
        get() = prefs.getString("tmdb_region", null)?.takeIf { it.length == 2 }
        set(v) = prefs.edit().apply { if (v == null) remove("tmdb_region") else putString("tmdb_region", v) }.apply()

    /** The streaming services you picked, by TMDB's provider ids as text. None picked: all show. */
    var tmdbServiceIds: Set<String>
        get() = prefs.getStringSet("tmdb_svc_ids", emptySet())!!
        set(v) = prefs.edit().putStringSet("tmdb_svc_ids", v).apply()

    /** Your own Watch Next list in place of the trending titles. */
    var watchNextOn: Boolean
        get() = prefs.getBoolean("wn_on", false)
        set(v) = prefs.edit().putBoolean("wn_on", v).apply()

    var watchNextProvider: String
        get() = prefs.getString("wn_provider", "trakt")!!.takeIf { it in WatchNext.PROVIDERS } ?: "trakt"
        set(v) = prefs.edit().putString("wn_provider", v).apply()

    /** The pinned apps by how often you open them (true), or in the order you set (false). */
    var favSortUsage: Boolean
        get() = prefs.getString("fav_sort", "custom") == "usage"
        set(v) = prefs.edit().putString("fav_sort", if (v) "usage" else "custom").apply()

    /** The backup also holds the TMDB key and the Watch Next sign-ins, as plain text. */
    var backupSecrets: Boolean
        get() = prefs.getBoolean("backup_tmdb", false)
        set(v) = prefs.edit().putBoolean("backup_tmdb", v).apply()
}
