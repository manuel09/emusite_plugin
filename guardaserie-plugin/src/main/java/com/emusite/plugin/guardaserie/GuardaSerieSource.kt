package com.emusite.plugin.guardaserie

import com.emusite.api.Source
import com.emusite.api.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class GuardaSerieSource : Source {
    override val id = "guardaserie"
    override val name = "GuardaSerie"
    override val baseUrl = "https://guardaserie.you"
    override val type = ContentType.TV_SERIES
    override val language = "it"
    override val isNsfw = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true).build()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        Jsoup.parse(client.newCall(req).execute().body?.string() ?: "")
    }

    override suspend fun getHomePage(): List<SearchResult> =
        getHomePageSections().flatMap { it.items }

    override suspend fun getHomePageSections(): List<HomePageSection> {
        val sections = listOf(
            "I titoli del momento" to "$baseUrl/i-titoli-del-momento/",
            "Top IMDB" to "$baseUrl/top-imdb/",
            "Aggiornamenti" to "$baseUrl/aggiornamenti-giornalieri.html",
            "Dramma" to "$baseUrl/dramma/",
            "Commedia" to "$baseUrl/commedia/",
            "Crime" to "$baseUrl/crime/",
            "Animazione" to "$baseUrl/animazione/",
            "Fantascienza" to "$baseUrl/fantascienza/",
            "Horror" to "$baseUrl/horror/",
            "Mistero" to "$baseUrl/mistero/",
        )
        return sections.mapNotNull { (name, url) ->
            try {
                val doc = get(url)
                val items = parseItems(doc)
                if (items.isNotEmpty()) HomePageSection(name, items.take(15)) else null
            } catch (_: Exception) { null }
        }
    }

    private fun parseItems(doc: Document): List<SearchResult> {
        return doc.select("a.ratio").mapNotNull { el ->
            val href = el.attr("href")
            val title = el.attr("title").ifBlank { el.select("img").attr("alt").ifBlank { return@mapNotNull null } }
            val poster = el.select("img").attr("src").let { if (it.startsWith("/")) "$baseUrl$it" else it }
            if (href.isNotBlank() && title.isNotBlank() && href != "#")
                SearchResult(href, title, poster.ifBlank { null }, null, ContentType.TV_SERIES, id)
            else null
        }
    }

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val doc = get("$baseUrl/?do=search&subaction=search&story=$query")
        return parseItems(doc)
    }

    override suspend fun getDetails(url: String): MediaDetails {
        val doc = get(url)
        val title = doc.select("h1").text().replace("streaming", "").trim()
        val poster = doc.select(".fimg img, .poster img").attr("src").let { if (it.startsWith("/")) "$baseUrl$it" else it }
        val plot = doc.select(".full-text, .fdesc").text().trim()
        val genres = doc.select(".fgenres a, .finfo a[href*=/genre/]").map { it.text() }
        val tmdbId = getTmdbId(title, doc, url)

        val episodes = if (tmdbId.isNotBlank() && tmdbId != "0") {
            getEpisodesFromTmdb(tmdbId)
        } else emptyList()

        return MediaDetails(
            id = url, title = title, description = plot.ifBlank { null },
            posterUrl = poster.ifBlank { null }, backdropUrl = poster.ifBlank { null },
            year = null, rating = null, genres = genres,
            type = ContentType.TV_SERIES, episodes = episodes
        )
    }

    override suspend fun getEpisodes(url: String): List<Episode> {
        val doc = get(url)
        val showTitle = doc.select("h1").text().replace("streaming", "").trim()
        val tmdbId = getTmdbId(showTitle, doc, url)
        return if (tmdbId.isNotBlank() && tmdbId != "0") getEpisodesFromTmdb(tmdbId) else emptyList()
    }

    private suspend fun getTmdbId(title: String, doc: Document, url: String): String {
        // From poster filename: tt10970762 -> 10970762
        val posterImg = doc.select(".fimg img, .poster img").attr("src")
        Regex("""tt(\d+)""").find(posterImg)?.groupValues?.get(1)?.let { return it }

        // From video player script: tt10970762 -> 10970762
        doc.select("script").forEach {
            val d = it.data()
            Regex("""tt(\d+)""").find(d)?.groupValues?.get(1)?.let { id -> return id }
        }

        // Fallback: search TMDB API
        try {
            val cleanTitle = title.replace(Regex("""\(.*?\)"""), "").trim()
            val q = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val req = Request.Builder().url("https://api.themoviedb.org/3/search/tv?api_key=7b5fcf48f24a334bb09f87ce20e5f2ce&language=it-IT&query=$q").build()
            val body = client.newCall(req).execute().body?.string() ?: return ""
            return json.decodeFromString<TmdbSearchResult>(body).results?.firstOrNull()?.id?.toString() ?: ""
        } catch (_: Exception) { return "" }
    }

    private fun getEpisodesFromTmdb(tmdbId: String): List<Episode> {
        val result = mutableListOf<Episode>()
        try {
            val apiUrl = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=7b5fcf48f24a334bb09f87ce20e5f2ce&language=it-IT"
            val req = Request.Builder().url(apiUrl).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return result
            val data = json.decodeFromString<TmdbShow>(body)
            data.seasons?.filter { it.seasonNumber > 0 }?.forEach { season ->
                val sUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/${season.seasonNumber}?api_key=7b5fcf48f24a334bb09f87ce20e5f2ce&language=it-IT"
                try {
                    val sResp = client.newCall(Request.Builder().url(sUrl).build()).execute()
                    val sBody = sResp.body?.string() ?: return@forEach
                    val sData = json.decodeFromString<TmdbSeasonData>(sBody)
                    sData.episodes?.forEach { ep ->
                        val epId = "tv:$tmdbId:${season.seasonNumber}:${ep.episodeNumber}"
                        result.add(Episode(epId, ep.name ?: "Ep ${ep.episodeNumber}",
                            season.seasonNumber, ep.episodeNumber, ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }, epId))
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return result
    }

    override suspend fun getStreamLinks(url: String): List<StreamLink> {
        val parts = url.split(":")
        if (parts.size < 4) return emptyList()
        val tmdbId = parts[1]
        val season = parts[2]
        val episode = parts[3]
        return extractVixStreams("https://vixsrc.to/tv/$tmdbId/$season/$episode?lang=it")
    }

    private suspend fun extractVixStreams(vixUrl: String): List<StreamLink> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(vixUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0").build()
            val html = client.newCall(req).execute().body?.string() ?: return@withContext emptyList()
            val mp = extractMasterPlaylist(html) ?: return@withContext emptyList()
            val u = mp.url + "?token=" + mp.params.token + "&expires=" + mp.params.expires
            listOf(StreamLink(url = if (mp.canPlayFHD) "$u&h=1" else u,
                quality = if (mp.canPlayFHD) "1080p" else "720p",
                headers = mapOf("Referer" to "https://vixsrc.to", "Origin" to "https://vixsrc.to",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")))
        } catch (_: Exception) { emptyList() }
    }

    private fun extractMasterPlaylist(html: String): VixPlaylist? {
        if (!html.contains("masterPlaylist")) return null
        return try {
            for (block in Regex("""<script[^>]*>([\s\S]*?)</script>""").findAll(html)) {
                val s = block.groupValues[1]
                if (!s.contains("masterPlaylist")) continue
                val root = json.decodeFromString<JsonObject>(extractJsObj(s))
                val mp = root["masterPlaylist"]?.toString() ?: continue
                val pl = json.decodeFromString<VixPlaylist>(mp)
                val fhd = root["canPlayFHD"]?.let { try { json.decodeFromString<Boolean>(it.toString()) } catch (_: Exception) { false } } ?: false
                if (pl.url.isNotBlank()) return pl.copy(canPlayFHD = fhd)
            }
            null
        } catch (_: Exception) { null }
    }

    private fun extractJsObj(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""").split(script).drop(1)
        val keys = Regex("""window\.(\w+)\s*=""").findAll(script).map { it.groupValues[1] }.toList()
        return "{" + keys.zip(parts).joinToString(",") { (k, v) ->
            "\"$k\":${v.replace(";","").trim()}"
        }.replace("'", "\"") + "}"
    }

    @Serializable data class TmdbSearchResult(val results: List<TmdbSearchItem>? = null)
    @Serializable data class TmdbSearchItem(val id: Int = 0)
    @Serializable data class TmdbShow(val seasons: List<TmdbSeason>? = null)
    @Serializable data class TmdbSeason(@kotlinx.serialization.SerialName("season_number") val seasonNumber: Int = 0)
    @Serializable data class TmdbSeasonData(val episodes: List<TmdbEp>? = null)
    @Serializable data class TmdbEp(@kotlinx.serialization.SerialName("episode_number") val episodeNumber: Int = 0,
                                     val name: String? = null,
                                     @kotlinx.serialization.SerialName("still_path") val stillPath: String? = null)

    @Serializable data class VixParams(val token: String = "", val expires: String = "")
    @Serializable data class VixPlaylist(val url: String = "", val params: VixParams = VixParams(), val canPlayFHD: Boolean = false)
}
