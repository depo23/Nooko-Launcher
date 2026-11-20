package dev.nooko.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbParseTest {
    @Test fun trendingKeepsFilmsAndShowsWithAName() {
        val page = JSONArray("""[
            {"id":1,"media_type":"movie","title":"A Film","overview":"o","backdrop_path":"/abc123.jpg","vote_average":7.84},
            {"id":2,"media_type":"tv","name":"A Show","overview":"","backdrop_path":"/def456.jpg","vote_average":0},
            {"id":3,"media_type":"person","name":"Someone","backdrop_path":"/x.jpg"},
            {"id":4,"media_type":"movie","title":"No picture","backdrop_path":null},
            {"id":5,"media_type":"movie","title":"","backdrop_path":"/ghi789.jpg"}]""")
        val heroes = Tmdb.parseTrending(page)
        assertEquals(listOf(1 to "movie", 2 to "tv"), heroes.map { it.id to it.type })
        assertEquals("https://image.tmdb.org/t/p/w780/abc123.jpg", heroes[0].backdrop)
        assertEquals("★ 7.8", heroes[0].scoreLabel())
        assertNull("no votes yet: no score", heroes[1].scoreLabel())
    }

    @Test fun heroNeedsABackdropAndAName() {
        assertNull(Tmdb.parseHero(JSONObject("""{"title":"T","backdrop_path":null}"""), "movie", 9))
        assertEquals("Show", Tmdb.parseHero(JSONObject("""{"name":"Show","backdrop_path":"/a1.jpg","vote_average":8}"""), "tv", 9)?.title)
    }

    private val details = JSONObject("""{
        "release_date":"2025-03-01","runtime":135,"genres":[{"name":"Sci-fi"},{"name":"Drama"},{"name":"Thriller"},{"name":"Extra"}],
        "credits":{"crew":[{"job":"Editor","name":"E"},{"job":"Director","name":"Dana"}],
                   "cast":[{"name":"A"},{"name":"B"},{"name":"C"},{"name":"D"}]},
        "watch/providers":{"results":{"BR":{
            "flatrate":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/n.png"},{"provider_id":337,"provider_name":"Disney+","logo_path":"/d.png"},
                        {"provider_id":99,"provider_name":"NoLogo","logo_path":null}],
            "rent":[{"provider_id":2,"provider_name":"Apple","logo_path":"/a.png"}]}}}}""")

    @Test fun detailsFilmReadsYearDirectorCastAndLength() {
        val d = Tmdb.parseDetails(details, "movie", "BR", emptySet())
        assertEquals("2025", d.year)
        assertEquals("Dana", d.director)
        assertEquals(listOf("A", "B", "C"), d.cast)
        assertEquals(listOf("Sci-fi", "Drama", "Thriller"), d.genres)
        assertEquals(135, d.runtime)
    }

    @Test fun servicesAreOnlyThoseWithALogoAndNotRentals() {
        val ids = Tmdb.parseDetails(details, "movie", "BR", emptySet()).providers.map { it.id }
        assertEquals(listOf(8, 337), ids) // Netflix before Disney+ in the order of the big ones; no logo and rent are left out
    }

    @Test fun onlyTheServicesYouPickedShow() {
        assertEquals(listOf(337), Tmdb.parseDetails(details, "movie", "BR", setOf(337)).providers.map { it.id })
        assertTrue(Tmdb.parseDetails(details, "movie", "IT", emptySet()).providers.isEmpty()) // nothing listed in that country
    }

    @Test fun seriesNamesItsCreatorsAndCounts() {
        val show = JSONObject("""{"first_air_date":"2020-01-01","created_by":[{"name":"X"},{"name":"Y"}],"number_of_seasons":3,"number_of_episodes":24}""")
        val d = Tmdb.parseDetails(show, "tv", "BR", emptySet())
        assertEquals("X, Y", d.director)
        assertEquals(3, d.seasons)
        assertEquals(24, d.episodes)
    }
}
