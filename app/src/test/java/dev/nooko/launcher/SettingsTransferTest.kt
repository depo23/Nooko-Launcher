package dev.nooko.launcher

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SettingsTransferTest {
    @Test fun exportsOnlyPortablePreferencesAndAppIds() {
        val result = SettingsTransfer.portable(mapOf(
            "theme" to "dark", "row_name" to "My shows", "lock_pin" to "secret", "setup_shown" to true,
            "upd_time" to 123L, "home_off" to false, "keymap" to "k183s4", "ss_folder" to "folder:5:Photos",
            "wp_light" to "video:local.mp4", "order" to "com.netflix.ninja,hdmi:local/HW1,org.videolan.vlc",
            "row_hidden" to setOf("hdmi:local/HW1", "com.spotify.tv.android"), "geo_lat" to 20f,
        ))
        assertEquals(setOf("theme", "row_name", "order", "row_hidden", "keymap"), result.keys) // the remote's keys travel; a photo folder on this TV does not
        assertEquals("com.netflix.ninja,org.videolan.vlc", result["order"])
        assertEquals(setOf("com.spotify.tv.android"), result["row_hidden"])
    }

    @Test fun validatesNumbersAndConvertsToPreferenceTypes() {
        val result = SettingsTransfer.validate(mapOf("wp_every" to 30L, "geo_manual" to true,
            "geo_city" to "London", "geo_lat" to 51.5, "geo_lon" to -0.1))
        assertEquals(30, result["wp_every"])
        assertEquals(51.5f, result["geo_lat"])
        assertEquals(-0.1f, result["geo_lon"])
    }

    @Test fun rejectsInvalidKnownValuesBeforeImport() {
        for (values in listOf(mapOf("clock_style" to "unknown"), mapOf("clock_size" to "999"),
            mapOf("app_anim" to "true"), mapOf("wp_every" to 1.5), mapOf("geo_manual" to true),
            mapOf("geo_lat" to Double.NaN), mapOf("geo_lon" to 181), mapOf("row_hidden" to listOf(3)),
            mapOf("order" to "hdmi:local"), mapOf("row_name" to "bad\u0000text"))) {
            assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(values) }
        }
    }

    @Test fun ignoresUnknownKeysAndKeepsNoSecrets() {
        assertEquals(mapOf("theme" to "light"), SettingsTransfer.validate(mapOf(
            "theme" to "light", "future_option" to mapOf("a" to true), "lock_pin" to "secret")))
        assertTrue(SettingsTransfer.validate(emptyMap<String, Any>()).isEmpty())
    }

    @Test fun acceptsAllPublishedDisplayChoices() {
        for (style in ICON_STYLE_KEYS) assertEquals(style, SettingsTransfer.validate(mapOf("icon_style" to style))["icon_style"])
        for (align in listOf("left", "centre", "right")) assertEquals(align, SettingsTransfer.validate(mapOf("row_align" to align))["row_align"])
    }

    @Test fun backsUpTheAppleTvLayoutAndWallpaperSources() {
        val saved = SettingsTransfer.portable(mapOf(
            "row_size" to "tv", "grid_v1" to true, "look_v2" to true,
            "home_hidden" to setOf("com.example.app", "tv.example"), "wh_topics" to setOf("nature", "space"),
            "wp_light" to "bing", "wp_dark" to "file:/storage/photo.jpg",
        ))
        assertEquals("usage", SettingsTransfer.validate(mapOf("fav_sort" to "usage"))["fav_sort"])
        assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(mapOf("fav_sort" to "random")) }
        assertEquals("0123456789abcdef0123456789abcdef", SettingsTransfer.validate(mapOf("tmdb_key" to "0123456789abcdef0123456789abcdef"))["tmdb_key"])
        assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(mapOf("tmdb_key" to "short")) }
        assertEquals("BR", SettingsTransfer.validate(mapOf("tmdb_region" to "BR"))["tmdb_region"])
        assertEquals(setOf("8", "350"), SettingsTransfer.validate(mapOf("tmdb_svc_ids" to setOf("8", "350")))["tmdb_svc_ids"])
        assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(mapOf("tmdb_region" to "bra")) }
        assertEquals("tv", saved["row_size"])
        assertEquals(setOf("com.example.app", "tv.example"), saved["home_hidden"])
        assertEquals("bing", saved["wp_light"])
        assertFalse("a picture on this TV does not travel", saved.containsKey("wp_dark"))
        assertEquals("wallhaven", SettingsTransfer.validate(mapOf("wp_light" to "reddit"))["wp_light"])
        assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(mapOf("wh_topics" to setOf("not a topic"))) }
    }

    @Test fun savesEveryUserSettingAndLeavesOutAValueThatDoesNotFit() {
        val saved = SettingsTransfer.portable(mapOf(
            "theme" to "light", "row_size" to "huge", // an unknown choice is left out, the rest is saved
            "cards_rows" to "per_channel", "ch_flip" to setOf("pkg/channel one"), "glass_hue" to -1, "smooth_volume" to true,
            "ss_folder" to "tmdb", "keymap" to "a=1\nb=2",
        ))
        assertEquals("light", saved["theme"])
        assertFalse(saved.containsKey("row_size"))
        assertEquals(setOf("pkg/channel one"), saved["ch_flip"])
        assertEquals("tmdb", saved["ss_folder"])
        assertFalse("a screensaver folder on this TV does not travel", SettingsTransfer.portable(mapOf("ss_folder" to "content://x/1")).containsKey("ss_folder"))
        assertEquals("{\"tmdb\":{\"session\":\"s\"}}", SettingsTransfer.validate(mapOf("wn_secrets" to "{\"tmdb\":{\"session\":\"s\"}}"))["wn_secrets"])
        assertThrows(IllegalArgumentException::class.java) { SettingsTransfer.validate(mapOf("wn_secrets" to "not json")) }
    }

    @Test fun failedCommitRestoresDestinationSettings() {
        val original = mapOf<String, Any>("theme" to "light", "clock_style" to "bold", "lock_pin" to "secret", "wx_time" to 123L)
        val stored = original.toMutableMap()
        var commits = 0
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> stored.toMap()
                "edit" -> {
                    val pending = stored.toMutableMap()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { editor, call, args ->
                        when (call.name) {
                            "remove" -> { pending.remove(args!![0]); editor }
                            "commit" -> { stored.clear(); stored.putAll(pending); ++commits > 1 }
                            else -> { pending[args!![0] as String] = args[1]; editor }
                        }
                    }
                }
                else -> error("Unexpected preference call")
            }
        } as SharedPreferences
        assertThrows(java.io.IOException::class.java) { SettingsTransfer.restore(prefs, mapOf("theme" to "dark")) }
        assertEquals(original, stored)
        assertEquals(2, commits)
    }
}
