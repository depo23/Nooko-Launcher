package dev.nooko.launcher

import dev.nooko.launcher.WatchNext.Ref
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNextParseTest {
    @Test fun traktMovieEpisodeAndShow() {
        val list = JSONArray("""[
            {"type":"movie","movie":{"ids":{"tmdb":11}}},
            {"type":"episode","show":{"ids":{"tmdb":22}},"episode":{"ids":{"tmdb":99}}},
            {"type":"show","show":{"ids":{"tmdb":33}}},
            {"type":"season","show":{"ids":{"tmdb":44}}},
            {"type":"movie","movie":{"ids":{"tmdb":null}}}]""")
        assertEquals(listOf(Ref("movie", 11), Ref("tv", 22), Ref("tv", 33)), WatchNext.parseTrakt(list))
    }

    @Test fun simklGroupsAndAnime() {
        val root = JSONObject("""{"shows":[{"show":{"ids":{"tmdb":"5"}}}],"movies":[{"movie":{"ids":{"tmdb":6}}}],
            "anime":[{"anime":{"ids":{"tmdb":7}}},{"anime":{"ids":{}}}]}""")
        assertEquals(listOf(Ref("tv", 5), Ref("movie", 6), Ref("tv", 7)), WatchNext.parseSimkl(root))
    }

    @Test fun mdblistUpNextAndWatchlist() {
        assertEquals(listOf(Ref("tv", 8)), WatchNext.parseMdbUpNext(JSONObject("""{"items":[{"show":{"ids":{"tmdb":8}},"next_episode":null}]}""")))
        val watchlist = JSONObject("""{"movies":[{"id":917496,"ids":{"tmdb":917496},"mediatype":"movie"}],"shows":[{"id":258902,"mediatype":"show"},{"id":0}]}""")
        assertEquals(listOf(Ref("movie", 917496), Ref("tv", 258902)), WatchNext.parseMdbWatchlist(watchlist))
    }
}
