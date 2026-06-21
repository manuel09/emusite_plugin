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

    override suspend fun getHomePageSections(): List<HomePageSection> {
        val sections = listOf(
            "I titoli del momento" to baseUrl,
            "Top IMDB" to "$baseUrl/archive?sort=vote",
            "Dramma" to "$baseUrl/archive?genre_id=18&type=tv",
            "Commedia" to "$baseUrl/archive?genre_id=35&type=tv",
            "Crime" to "$baseUrl/archive?genre_id=80&type=tv",
            "Animazione" to "$baseUrl/archive?genre_id=16&type=tv",
            "Sci-Fi & Fantasy" to "$baseUrl/archive?genre_id=10765&type=tv",
            "Mistero" to "$baseUrl/archive?genre_id=9648&type=tv",
        )
        return sections.mapNotNull { (name, url) ->
            try {
                val doc = get(url)
                val items = parseItems(doc, name)
                if (items.isNotEmpty()) HomePageSection(name, items) else null
            } catch (_: Exception) { null }
        }
    }

    override suspend fun getHomePage(): List<SearchResult> = 
        getHomePageSections().flatMap { it.items }

    private fun parseItems(doc: Document, section: String): List<SearchResult> {
        val items = mutableListOf<SearchResult>()

        // Slider items on main page
        doc.select(".slider-item a").forEach { el ->
            val link = el.attr("href")
            val title = el.select("img").attr("alt")
            val poster = el.select("img").attr("src").let { if (it.startsWith("/")) "$baseUrl$it" else it }
            if (link.isNotBlank() && title.isNotBlank())
                items.add(SearchResult(link, title, poster, null, ContentType.TV_SERIES, id))
        }

        // Ranked list items
        doc.select("#ranked-list ul li a.ranked-link").forEach { el ->
            val link = el.attr("href")
            val title = el.select(".rank-name").text()
            if (link.isNotBlank() && title.isNotBlank())
                items.add(SearchResult(link, title, null, null, ContentType.TV_SERIES, id))
        }

        // Grid items
        doc.select(".mlnew").forEach { el ->
            val link = el.select(".mlnh-thumb a").attr("href")
            val title = el.select(".mlnh-2 h2 a").text()
            val poster = el.select(".mlnh-thumb img").attr("src").let { if (it.startsWith("/")) "$baseUrl$it" else it }
            if (link.isNotBlank() && title.isNotBlank())
                items.add(SearchResult(link, title, poster, null, ContentType.TV_SERIES, id))
        }

        return items.distinctBy { it.id }.take(20)
    }

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val doc = get("$baseUrl/search?q=${query.replace(" ", "+")}")
        val items = mutableListOf<SearchResult>()

        doc.select("#ranked-list ul li a.ranked-link").forEach { el ->
            val link = el.attr("href")
            if (!link.contains("/detail/tv-")) return@forEach
            items.add(SearchResult(link, el.select(".rank-name").text(), null, null, ContentType.TV_SERIES, id))
        }
        doc.select(".mlnew").forEach { el ->
            val link = el.select(".mlnh-thumb a").attr("href")
            if (!link.contains("/detail/tv-")) return@forEach
            val poster = el.select(".mlnh-thumb img").attr("src").let { if (it.startsWith("/")) "$baseUrl$it" else it }
            items.add(SearchResult(link, el.select(".mlnh-2 h2 a").text(), poster, null, ContentType.TV_SERIES, id))
        }
        return items
    }

    override suspend fun getDetails(url: String): MediaDetails {
        val doc = get(url)
        val title = doc.select("h1.front-title, .gs-detail-title").text().replace("streaming", "").trim()
        val poster = doc.select("#tv-info-poster img").attr("src").let { if (it.startsWith("/")) "$baseUrl$it" else it }
        val plot = doc.select(".tv-info-right").text().substringAfter("Trama").substringBefore("Categoria").trim()
        val rating = doc.select(".entry-imdb").text().replace("★", "").trim().toFloatOrNull()
        val genres = doc.select(".tv-info-list ul:contains(Categoria) li:last-child a").map { it.text() }
        val yearText = doc.select(".tv-info-list ul:contains(Anno) li:last-child").text()
        val year = Regex("\\d{4}").find(yearText)?.value?.toIntOrNull()

        val tmdbId = extractTmdbId(url, doc)

        val episodes = getEpisodesFromDoc(doc, tmdbId)

        return MediaDetails(
            id = url, title = title, description = plot.ifBlank { null },
            posterUrl = poster.ifBlank { null }, backdropUrl = poster.ifBlank { null },
            year = year, rating = rating, genres = genres,
            type = ContentType.TV_SERIES, episodes = episodes
        )
    }

    override suspend fun getEpisodes(url: String): List<Episode> {
        val doc = get(url)
        val tmdbId = extractTmdbId(url, doc)
        return getEpisodesFromDoc(doc, tmdbId)
    }

    private fun extractTmdbId(url: String, doc: Document): String {
        Regex("""/detail/tv-(\d+)""").find(url)?.groupValues?.get(1)?.let { return it }
        doc.select("script").forEach { script ->
            Regex("""var tmdbID\s*=\s*(\d+);""").find(script.data())?.groupValues?.get(1)?.let { return it }
        }
        Regex("""tv-(\d+)""").find(doc.select("meta[property=og:url]").attr("content"))?.groupValues?.get(1)?.let { return it }
        return "0"
    }

    private fun getEpisodesFromDoc(doc: Document, tmdbId: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        doc.select(".tt-season ul li a").forEach { seasonTab ->
            val seasonNum = seasonTab.text().toIntOrNull() ?: return@forEach
            val seasonId = seasonTab.attr("href").removePrefix("#")
            doc.select("#$seasonId ul li").forEach { epItem ->
                val epLink = epItem.select("a").first() ?: return@forEach
                val epNum = epLink.attr("data-episode").toIntOrNull() ?: return@forEach
                val title = epLink.attr("data-title").trim().ifBlank { "Episodio $epNum" }
                val vixUrl = "tv:$tmdbId:$seasonNum:$epNum"
                episodes.add(Episode(vixUrl, title, seasonNum, epNum, null, vixUrl))
            }
        }
        return episodes
    }

    override suspend fun getStreamLinks(url: String): List<StreamLink> {
        val parts = url.split(":")
        if (parts.size < 4) return emptyList()
        val tmdbId = parts[1].toIntOrNull() ?: return emptyList()
        val season = parts[2].toIntOrNull() ?: return emptyList()
        val episode = parts[3].toIntOrNull() ?: return emptyList()

        val vixUrl = "https://vixsrc.to/tv/$tmdbId/$season/$episode?lang=it"
        return extractVixStreams(vixUrl)
    }

    private suspend fun extractVixStreams(vixUrl: String): List<StreamLink> = withContext(Dispatchers.IO) {
        try {
            val apiReq = Request.Builder().url(vixUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")
                .build()
            val apiResp = client.newCall(apiReq).execute()
            val apiHtml = apiResp.body?.string() ?: return@withContext emptyList()

            val mp = extractMasterPlaylist(apiHtml) ?: return@withContext emptyList()
            val u = mp.url + "?token=" + mp.params.token + "&expires=" + mp.params.expires
            val finalUrl = if (mp.canPlayFHD) "$u&h=1" else u

            listOf(StreamLink(url = finalUrl, quality = if (mp.canPlayFHD) "1080p" else "720p",
                headers = mapOf("Referer" to "https://vixsrc.to", "Origin" to "https://vixsrc.to",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")))
        } catch (_: Exception) { emptyList() }
    }

    private fun extractMasterPlaylist(html: String): VixPlaylist? {
        if (!html.contains("masterPlaylist")) return null
        return try {
            val blocks = Regex("""<script[^>]*>([\s\S]*?)</script>""").findAll(html)
            for (block in blocks) {
                val script = block.groupValues[1]
                if (!script.contains("masterPlaylist")) continue
                val extracted = extractJsObj(script)
                val root = json.decodeFromString<JsonObject>(extracted)
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
        val objs = keys.zip(parts).map { (k, v) ->
            val clean = v.replace(";", "").replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1").trim()
            "\"$k\": $clean"
        }
        return "{\n${objs.joinToString(",\n")}\n}".replace("'", "\"")
    }

    @Serializable data class VixParams(val token: String = "", val expires: String = "")
    @Serializable data class VixPlaylist(val url: String = "", val params: VixParams = VixParams(), val canPlayFHD: Boolean = false)
}
