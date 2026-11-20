package dev.nooko.launcher

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Your own Watch Next list, from Trakt, Simkl, MDBList or TMDB, shown in the TMDB cards and fullscreen in place of the week's
 * trending titles. Every service names its titles by TMDB id, so the pictures, details and logos still come from TMDB.
 * What a service needs from you is kept in one private file (never in the backup): an API key (MDBList), or a client id and a
 * code you enter on the service's website (Trakt, Simkl), or an approval on TMDB's website.
 */
internal object WatchNext {
    val PROVIDERS = linkedMapOf("trakt" to "Trakt", "simkl" to "Simkl", "mdblist" to "MDBList", "tmdb" to "TMDB")

    /** A title as the services list it: "movie" or "tv", and the TMDB id. */
    data class Ref(val type: String, val id: Int)

    /** What the person does to sign in: a code to enter at [url], and what to poll with. */
    class Device(val userCode: String, val url: String, val deviceCode: String, val interval: Int, val expiresIn: Int, val qr: String = url)

    enum class Poll { WAIT, DONE, FAIL }

    fun provider(prefs: SharedPreferences) = Settings(prefs).watchNextProvider

    // ---------- what is kept ----------

    private fun file(c: Context) = AtomicFile(File(c.noBackupFilesDir, "watchnext.json"))

    @Synchronized
    private fun read(c: Context): JSONObject = try { JSONObject(file(c).readFully().decodeToString()) } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun put(c: Context, provider: String, key: String, value: String?) {
        val all = read(c)
        val mine = all.optJSONObject(provider) ?: JSONObject().also { all.put(provider, it) }
        if (value == null) mine.remove(key) else mine.put(key, value)
        val f = file(c)
        val out = f.startWrite()
        try {
            out.write(all.toString().encodeToByteArray())
            f.finishWrite(out)
        } catch (e: IOException) {
            f.failWrite(out)
            throw e
        }
    }

    /** Everything kept for the services as one text, for a backup that includes sign-ins; null when nothing is kept. */
    fun exportJson(c: Context): String? = read(c).takeIf { it.length() > 0 }?.toString()

    /** Puts back what [exportJson] gave. */
    @Synchronized
    fun importJson(c: Context, text: String) {
        val parsed = JSONObject(text)
        val all = JSONObject() // only what this app keeps: a service it knows, and the texts under it
        for (provider in PROVIDERS.keys) parsed.optJSONObject(provider)?.let { all.put(provider, it) }
        val f = file(c)
        val out = f.startWrite()
        try {
            out.write(all.toString().encodeToByteArray())
            f.finishWrite(out)
        } catch (e: IOException) {
            f.failWrite(out)
            throw e
        }
    }

    fun get(c: Context, provider: String, key: String): String? = read(c).optJSONObject(provider)?.optString(key)?.takeIf { it.isNotEmpty() }

    /** Signed out: the token and what came with it go, the client id and key you typed stay. */
    fun signOut(c: Context, provider: String) {
        for (k in listOf("token", "refresh", "session", "account")) put(c, provider, k, null)
    }

    /** The service has what it needs to list your titles. */
    fun ready(c: Context, provider: String): Boolean = when (provider) {
        "mdblist" -> get(c, provider, "token") != null || get(c, provider, "key") != null
        "tmdb" -> get(c, provider, "session") != null && get(c, provider, "account") != null && Tmdb.key(c) != null
        else -> get(c, provider, "token") != null
    }

    fun active(c: Context, prefs: SharedPreferences) = Settings(prefs).watchNextOn && ready(c, provider(prefs))

    // ---------- the web ----------

    private fun http(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null, form: String? = null): Http.Response =
        Http.send(url, method, headers, json = body, form = form)

    internal fun tmdbId(ids: JSONObject?): Int? = ids?.opt("tmdb")?.toString()?.toIntOrNull()?.takeIf { it > 0 }

    // ---------- signing in ----------

    fun start(c: Context, provider: String): Device = when (provider) {
        "trakt" -> {
            val id = get(c, provider, "id") ?: throw IOException("Enter the client ID first")
            val r = http("https://api.trakt.tv/oauth/device/code", "POST", body = JSONObject().put("client_id", id).toString())
            if (r.code != 200) throw IOException("Trakt answered ${r.code}")
            val j = JSONObject(r.body)
            // Trakt's own activation page; its answer names the address too.
            Device(j.getString("user_code"), TRAKT_ACTIVATE, j.getString("device_code"), j.optInt("interval", 5), j.optInt("expires_in", 600))
        }
        "simkl" -> {
            val id = get(c, provider, "id") ?: throw IOException("Enter the client ID first")
            val r = http("https://api.simkl.com/oauth/pin?client_id=$id")
            if (r.code != 200) throw IOException("Simkl answered ${r.code}")
            val j = JSONObject(r.body)
            Device(j.getString("user_code"), j.optString("verification_url", "https://simkl.com/pin/"), j.getString("user_code"),
                j.optInt("interval", 5), j.optInt("expires_in", 900))
        }
        "mdblist" -> {
            val id = get(c, provider, "id") ?: throw IOException("Enter the client ID first")
            val r = http("https://api.mdblist.com/oauth/device-authorization/", "POST", form = "client_id=$id&scope=write")
            if (r.code != 200) throw IOException("MDBList answered ${r.code}")
            val j = JSONObject(r.body)
            val url = j.optString("verification_uri", "https://mdblist.com/oauth/device/")
            Device(j.getString("user_code"), url, j.getString("device_code"), j.optInt("interval", 5), j.optInt("expires_in", 300),
                qr = j.optString("verification_uri_complete").ifEmpty { url })
        }
        "tmdb" -> {
            val key = Tmdb.key(c) ?: throw IOException("Add a TMDB key first")
            val r = http("https://api.themoviedb.org/3/authentication/token/new?api_key=$key")
            if (r.code != 200) throw IOException("TMDB answered ${r.code}")
            val token = JSONObject(r.body).getString("request_token")
            Device("", "https://www.themoviedb.org/authenticate/$token", token, 4, 600)
        }
        else -> throw IOException("This service has no sign-in")
    }

    /** One look at whether the person has approved yet; on approval the token (or session) is kept. */
    fun poll(c: Context, provider: String, d: Device): Poll = when (provider) {
        "trakt" -> {
            val id = get(c, provider, "id").orEmpty()
            val secret = get(c, provider, "secret").orEmpty()
            val r = http("https://api.trakt.tv/oauth/device/token", "POST",
                body = JSONObject().put("code", d.deviceCode).put("client_id", id).put("client_secret", secret).toString())
            when (r.code) {
                200 -> {
                    val j = JSONObject(r.body)
                    put(c, provider, "token", j.getString("access_token"))
                    put(c, provider, "refresh", j.optString("refresh_token").ifEmpty { null })
                    Poll.DONE
                }
                400, 429 -> Poll.WAIT // not yet, or too fast
                else -> Poll.FAIL
            }
        }
        "simkl" -> {
            val id = get(c, provider, "id").orEmpty()
            val r = http("https://api.simkl.com/oauth/pin/${d.deviceCode}?client_id=$id")
            val j = try { JSONObject(r.body) } catch (e: Exception) { JSONObject() }
            if (r.code == 200 && j.optString("access_token").isNotEmpty()) {
                put(c, provider, "token", j.getString("access_token"))
                Poll.DONE
            } else if (r.code == 200 || r.code == 429) Poll.WAIT else Poll.FAIL
        }
        "mdblist" -> {
            val id = get(c, provider, "id").orEmpty()
            val r = http("https://api.mdblist.com/oauth/token/", "POST",
                form = "grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=${d.deviceCode}&client_id=$id")
            val j = try { JSONObject(r.body) } catch (e: Exception) { JSONObject() }
            if (r.code == 200 && j.optString("access_token").isNotEmpty()) {
                put(c, provider, "token", j.getString("access_token"))
                put(c, provider, "refresh", j.optString("refresh_token").ifEmpty { null })
                Poll.DONE
            } else if (j.optString("error") == "authorization_pending" || j.optString("error") == "slow_down" || r.code == 429) Poll.WAIT
            else Poll.FAIL // denied, expired, or something this app cannot mend
        }
        "tmdb" -> {
            val key = Tmdb.key(c).orEmpty()
            val r = http("https://api.themoviedb.org/3/authentication/session/new?api_key=$key", "POST",
                body = JSONObject().put("request_token", d.deviceCode).toString())
            when (r.code) {
                200 -> {
                    val session = JSONObject(r.body).getString("session_id")
                    val account = JSONObject(http("https://api.themoviedb.org/3/account?api_key=$key&session_id=$session").body).optInt("id")
                    if (account <= 0) Poll.FAIL else {
                        put(c, provider, "session", session)
                        put(c, provider, "account", account.toString())
                        Poll.DONE
                    }
                }
                401 -> Poll.WAIT // not approved yet
                else -> Poll.FAIL
            }
        }
        else -> Poll.FAIL
    }

    // ---------- the lists ----------

    private fun traktHeaders(c: Context) = mapOf(
        "trakt-api-version" to "2", "trakt-api-key" to get(c, "trakt", "id").orEmpty(), "Authorization" to "Bearer ${get(c, "trakt", "token").orEmpty()}")

    /** One refresh of an expired Trakt token. */
    private fun traktRefresh(c: Context): Boolean {
        val refresh = get(c, "trakt", "refresh") ?: return false
        val r = http("https://api.trakt.tv/oauth/token", "POST", body = JSONObject().put("refresh_token", refresh)
            .put("client_id", get(c, "trakt", "id").orEmpty()).put("client_secret", get(c, "trakt", "secret").orEmpty())
            .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token").toString())
        if (r.code != 200) return false
        val j = JSONObject(r.body)
        put(c, "trakt", "token", j.getString("access_token"))
        put(c, "trakt", "refresh", j.optString("refresh_token").ifEmpty { null })
        return true
    }

    private fun traktGet(c: Context, path: String): JSONArray {
        var r = http("https://api.trakt.tv$path", headers = traktHeaders(c))
        if (r.code == 401 && traktRefresh(c)) r = http("https://api.trakt.tv$path", headers = traktHeaders(c))
        if (r.code != 200) throw IOException("Trakt answered ${r.code} for $path")
        return JSONArray(r.body)
    }

    /** MDBList: the signed-in token if there is one, else the API key; one refresh of an expired token. */
    private fun mdbGet(c: Context, path: String): String {
        fun call(): Http.Response {
            val token = get(c, "mdblist", "token")
            val sep = if ('?' in path) '&' else '?'
            return if (token != null) http("https://api.mdblist.com$path", headers = mapOf("Authorization" to "Bearer $token"))
            else http("https://api.mdblist.com$path${sep}apikey=${get(c, "mdblist", "key").orEmpty()}")
        }
        var r = call()
        if (r.code == 401 && get(c, "mdblist", "refresh") != null) {
            val t = http("https://api.mdblist.com/oauth/token/", "POST",
                form = "grant_type=refresh_token&refresh_token=${get(c, "mdblist", "refresh")}&client_id=${get(c, "mdblist", "id").orEmpty()}")
            if (t.code == 200) {
                val j = JSONObject(t.body)
                put(c, "mdblist", "token", j.getString("access_token"))
                put(c, "mdblist", "refresh", j.optString("refresh_token").ifEmpty { null })
                r = call()
            }
        }
        if (r.code != 200) throw IOException("MDBList answered ${r.code} for $path")
        return r.body
    }

    /** Trakt's playback and watchlist answers: a movie, an episode (its show) or a show, each with TMDB ids. */
    fun parseTrakt(list: JSONArray): List<Ref> = (0 until list.length()).mapNotNull { i ->
        val item = list.getJSONObject(i)
        when (item.optString("type")) {
            "movie" -> tmdbId(item.optJSONObject("movie")?.optJSONObject("ids"))?.let { Ref("movie", it) }
            "episode", "show" -> tmdbId(item.optJSONObject("show")?.optJSONObject("ids"))?.let { Ref("tv", it) }
            else -> null
        }
    }

    /** Simkl's all-items answer: groups of shows, movies and anime, each item holding its ids. */
    fun parseSimkl(root: JSONObject): List<Ref> = listOf("shows", "movies", "anime").flatMap { group ->
        val list = root.optJSONArray(group) ?: return@flatMap emptyList<Ref>()
        (0 until list.length()).mapNotNull { i ->
            val item = list.getJSONObject(i)
            val movie = item.optJSONObject("movie")
            if (movie != null) tmdbId(movie.optJSONObject("ids"))?.let { Ref("movie", it) }
            else tmdbId((item.optJSONObject("show") ?: item.optJSONObject("anime"))?.optJSONObject("ids"))?.let { Ref("tv", it) }
        }
    }

    /** MDBList's Up Next answer: shows with their next episode. */
    fun parseMdbUpNext(root: JSONObject): List<Ref> {
        val list = root.optJSONArray("items") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i -> tmdbId(list.getJSONObject(i).optJSONObject("show")?.optJSONObject("ids"))?.let { Ref("tv", it) } }
    }

    /** MDBList's watchlist answer: movies and shows; an item's `id` is its TMDB id. */
    fun parseMdbWatchlist(root: JSONObject): List<Ref> = listOf("movies", "shows").flatMap { group ->
        val list = root.optJSONArray(group) ?: return@flatMap emptyList<Ref>()
        (0 until list.length()).mapNotNull { i ->
            val item = list.getJSONObject(i)
            val id = (item.optJSONObject("ids")?.opt("tmdb") ?: item.opt("tmdb_id") ?: item.opt("id"))?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            Ref(if (group == "shows" || item.optString("mediatype") == "show") "tv" else "movie", id)
        }
    }

    /** The titles of [provider]'s Watch Next list, the newest first. Throws when the service cannot be reached. */
    fun fetch(c: Context, provider: String): List<Ref> {
        val refs = ArrayList<Ref>()
        when (provider) {
            "trakt" -> {
                // What you are part way through, then your watchlist.
                for (path in listOf("/sync/playback?limit=20", "/sync/watchlist?limit=40")) {
                    val list = try { traktGet(c, path) } catch (e: IOException) { if (refs.isEmpty() && path.contains("watchlist")) throw e else JSONArray() }
                    refs += parseTrakt(list)
                }
            }
            "simkl" -> {
                val headers = mapOf("Authorization" to "Bearer ${get(c, provider, "token").orEmpty()}", "simkl-api-key" to get(c, provider, "id").orEmpty())
                for ((kind, status) in listOf("shows" to "watching", "movies" to "plantowatch", "shows" to "plantowatch")) {
                    val r = http("https://api.simkl.com/sync/all-items/$kind/$status", headers = headers)
                    if (r.code != 200) throw IOException("Simkl answered ${r.code}")
                    val root = try { JSONObject(r.body) } catch (e: Exception) { continue } // an empty list can come back as nothing
                    refs += parseSimkl(root)
                }
            }
            "mdblist" -> {
                // What you are part way through (shows with their next episode), then your watchlist.
                runCatching { JSONObject(mdbGet(c, "/upnext?limit=20")) }.getOrNull()?.let { refs += parseMdbUpNext(it) }
                refs += parseMdbWatchlist(JSONObject(mdbGet(c, "/watchlist/items?limit=40")))
            }
            "tmdb" -> {
                val key = Tmdb.key(c) ?: throw IOException("Add a TMDB key first")
                val account = get(c, provider, "account").orEmpty()
                val session = get(c, provider, "session").orEmpty()
                for ((kind, type) in listOf("movies" to "movie", "tv" to "tv")) {
                    val r = http("https://api.themoviedb.org/3/account/$account/watchlist/$kind?api_key=$key&session_id=$session&sort_by=created_at.desc")
                    if (r.code != 200) throw IOException("TMDB answered ${r.code}")
                    val list = JSONObject(r.body).optJSONArray("results") ?: continue
                    for (i in 0 until list.length()) refs += Ref(type, list.getJSONObject(i).optInt("id"))
                }
            }
        }
        return refs.distinct().filter { it.id > 0 }
    }

    /** The titles as TMDB cards: up to [max], in the service's order, a few fetched at once. */
    fun heroes(c: Context, prefs: SharedPreferences, key: String, max: Int = 30): List<Tmdb.Hero> {
        val refs = fetch(c, provider(prefs)).take(max + 10) // a few spare: a title without a picture is left out
        val pool = Executors.newFixedThreadPool(4)
        try {
            val found = pool.invokeAll(refs.map { r -> Callable { try { Tmdb.hero(r.type, r.id, key) } catch (e: Exception) { null } } })
            return found.mapNotNull { it.get() }.take(max)
        } finally {
            pool.shutdown()
        }
    }

    const val TRAKT_ACTIVATE = "https://auth.trakt.tv/activate"
}
