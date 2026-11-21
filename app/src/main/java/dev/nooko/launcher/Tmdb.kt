package dev.nooko.launcher

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

internal object Tmdb {
    private val keyPattern = Regex("[a-fA-F0-9]{32}")
    private val imagePath = Regex("/[a-zA-Z0-9]+\\.jpg")
    /** A service logo's file on TMDB's image server: a service can have a png or another kind of name than a poster. */
    private val logoPath = Regex("/[A-Za-z0-9_-]+\\.(?:jpe?g|png|webp)")

    private fun keyFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "tmdb-key"))

    fun validKey(key: String) = keyPattern.matches(key)

    fun key(context: Context): String? = try {
        keyFile(context).readFully().decodeToString().takeIf(::validKey)
    } catch (_: IOException) {
        null
    }

    fun saveKey(context: Context, key: String?) {
        val file = keyFile(context)
        val output = file.startWrite()
        try {
            output.write(key.orEmpty().encodeToByteArray())
            file.finishWrite(output)
        } catch (e: IOException) {
            file.failWrite(output)
            throw e
        }
    }

    fun acceptsKey(key: String) = request("authentication", key)?.optBoolean("success") == true

    data class Hero(val id: Int, val type: String, val title: String, val overview: String, val backdrop: String, val score: Double = 0.0) {
        /** TMDB's user score out of ten, as a short label; nothing for a title with no votes yet. */
        fun scoreLabel(): String? = if (score > 0.0) String.format(Locale.ROOT, "★ %.1f", score) else null
    }

    /**
     * 30 of this week's trending films and shows that have a backdrop, in a new random order each time. TMDB gives 20 a page,
     * so the first five pages are read (100) and 30 are picked from them: a wider pool, so the row changes more from one refresh to the next.
     */
    fun trending(key: String): List<Hero> {
        val found = (1..5).flatMap { page ->
            val results = request("trending/all/week?page=$page", key)?.getJSONArray("results") ?: throw IOException("TMDB rejected the key")
            parseTrending(results)
        }
        return found.distinctBy { it.type to it.id }.shuffled().take(30)
    }

    /** The films and shows of a trending page that have a name and a backdrop. */
    fun parseTrending(results: org.json.JSONArray): List<Hero> = (0 until results.length()).mapNotNull { i ->
        val item = results.getJSONObject(i)
        val kind = item.optString("media_type")
        val name = item.optString(if (kind == "movie") "title" else "name")
        val path = item.optString("backdrop_path")
        if ((kind != "movie" && kind != "tv") || name.isEmpty() || !imagePath.matches(path)) null
        else Hero(item.optInt("id"), kind, name, item.optString("overview"), "https://image.tmdb.org/t/p/w780$path", item.optDouble("vote_average", 0.0))
    }

    /** One title as a card: its name, synopsis, score and backdrop; null when TMDB has no backdrop for it. */
    fun hero(type: String, id: Int, key: String): Hero? = request("$type/$id", key)?.let { parseHero(it, type, id) }

    fun parseHero(json: JSONObject, type: String, id: Int): Hero? {
        val name = json.optString(if (type == "movie") "title" else "name")
        val path = json.optString("backdrop_path")
        if (name.isEmpty() || !imagePath.matches(path)) return null
        return Hero(id, type, name, json.optString("overview"), "https://image.tmdb.org/t/p/w780$path", json.optDouble("vote_average", 0.0))
    }

    /** TMDB's ids for Apple TV+, Netflix, Prime Video, Max/HBO, Crunchyroll and Disney+ (a service can have more than one), shown first. */
    private val MAIN_SERVICES = listOf(350, 2, 8, 1796, 9, 119, 1899, 384, 118, 283, 337)

    /** The services most people mean: Netflix, Prime Video, Disney+, Apple TV+, Max / HBO, Paramount+, Hulu, Peacock, Crunchyroll, YouTube. */
    val CORE = setOf(8, 9, 2100, 337, 350, 1899, 384, 531, 15, 386, 387, 283, 188)

    data class Provider(val name: String, val logo: String, val id: Int = 0, val mine: Boolean = false)

    /** The country whose services are shown: the one chosen in settings, else the TV's own. */
    fun region(prefs: android.content.SharedPreferences): String =
        Settings(prefs).tmdbRegion ?: Locale.getDefault().country.ifEmpty { "US" }

    /** The services you picked, by TMDB's provider ids. None picked means all of them show. */
    fun mine(prefs: android.content.SharedPreferences): Set<Int> =
        Settings(prefs).tmdbServiceIds.mapNotNull { it.toIntOrNull() }.toSet()

    /** The streaming services TMDB lists in [region], for films and series, the most used first. Live from TMDB. */
    fun providers(key: String, region: String): List<Provider> {
        val found = listOf("movie", "tv").flatMap { kind ->
            val list = request("watch/providers/$kind?watch_region=$region", key)?.getJSONArray("results") ?: throw IOException("TMDB rejected the key")
            (0 until list.length()).map { list.getJSONObject(it) }
        }
        return found.distinctBy { it.optInt("provider_id") }.filter { logoPath.matches(it.optString("logo_path")) }
            .sortedBy { it.optJSONObject("display_priorities")?.optInt(region, 999) ?: 999 }
            .map { Provider(it.optString("provider_name"), "https://image.tmdb.org/t/p/w92${it.optString("logo_path")}", it.optInt("provider_id")) }
    }

    /** Every country TMDB has streaming data for: code and name. */
    fun regions(key: String): List<Pair<String, String>> {
        val list = request("watch/providers/regions", key)?.getJSONArray("results") ?: throw IOException("TMDB rejected the key")
        return (0 until list.length()).map { list.getJSONObject(it) }.map { it.optString("iso_3166_1") to it.optString("english_name") }
            .filter { it.first.length == 2 }.sortedBy { it.second }
    }

    data class Details(val year: String, val director: String, val genres: List<String>, val cast: List<String>, val providers: List<Provider>, val rentOnly: Boolean = false, val region: String = "", val failed: Boolean = false,
        val runtime: Int = 0, val seasons: Int = 0, val episodes: Int = 0)

    /** Year, director (or creator), top cast and flat-rate streaming services in [region], in one request. */
    fun details(hero: Hero, key: String, region: String, mine: Set<Int> = emptySet()): Details {
        val json = request("${hero.type}/${hero.id}?append_to_response=credits,watch/providers", key) ?: throw IOException("TMDB rejected the key")
        val d = parseDetails(json, hero.type, region, mine)
        android.util.Log.i("ATV", "tmdb ${hero.type}/${hero.id} region=$region logos=${d.providers.size} cast=${d.cast.size} genres=${d.genres.size}")
        return d
    }

    /** What the details answer holds: year, director (or creator), top cast, genres, length and the services you picked in [region]. */
    fun parseDetails(json: JSONObject, type: String, region: String, mine: Set<Int>): Details {
        val date = json.optString(if (type == "movie") "release_date" else "first_air_date")
        val crew = json.optJSONObject("credits")?.optJSONArray("crew")
        val director = if (type == "movie") (0 until (crew?.length() ?: 0)).map { crew!!.getJSONObject(it) }
            .firstOrNull { it.optString("job") == "Director" }?.optString("name").orEmpty()
        else json.optJSONArray("created_by")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty()
        val cast = json.optJSONObject("credits")?.optJSONArray("cast")
        val names = (0 until minOf(3, cast?.length() ?: 0)).map { cast!!.getJSONObject(it).optString("name") }
        val inRegion = json.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(region)
        fun list(kind: String) = inRegion?.optJSONArray(kind)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val stream = list("flatrate") + list("free") + list("ads")
        // Only services with a logo, and only the ones you picked (all of them when you picked none). The big ones come first.
        val chosen = stream.filter { mine.isEmpty() || it.optInt("provider_id") in mine }
        val providers = chosen.distinctBy { it.optInt("provider_id") }.filter { logoPath.matches(it.optString("logo_path")) }
            .sortedBy { MAIN_SERVICES.indexOf(it.optInt("provider_id")).let { i -> if (i < 0) MAIN_SERVICES.size else i } }.take(6)
            .map { Provider(it.optString("provider_name"), "https://image.tmdb.org/t/p/w92${it.optString("logo_path")}", it.optInt("provider_id"), true) }
        val genreList = json.optJSONArray("genres")
        val genres = (0 until minOf(3, genreList?.length() ?: 0)).map { genreList!!.getJSONObject(it).optString("name") }
        return Details(date.take(4), director, genres, names, providers, false, region,
            runtime = json.optInt("runtime"), seasons = json.optInt("number_of_seasons"), episodes = json.optInt("number_of_episodes"))
    }

    /** The TV's language, as TMDB writes it ("it-IT"): titles, synopses and genres come in it. */
    private fun language(): String {
        val l = Locale.getDefault()
        val code = when (l.language) { "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; else -> l.language } // Android's old codes
        return if (l.country.isEmpty()) code else "$code-${l.country}"
    }

    private fun request(path: String, key: String): JSONObject? {
        val separator = if ('?' in path) '&' else '?'
        val r = Http.send("https://api.themoviedb.org/3/$path${separator}api_key=$key&language=${language()}", connectMs = 5000, readMs = 5000)
        return when {
            r.code == 200 -> JSONObject(r.body)
            r.code == 401 || r.code == 403 -> null
            else -> throw IOException("TMDB request failed: HTTP ${r.code}")
        }
    }
}
