package com.emusite.plugin.onlineserietv

import com.emusite.api.Source
import com.emusite.api.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.SocketTimeoutException

class OnlineSerieTVSource : Source {
    override val id = "onlineserietv"
    override val name = "OnlineSerieTV"
    override val baseUrl = "https://lingering-truth-455c.diegon7771.workers.dev"
    override val type = ContentType.TV_SERIES
    override val language = "it"
    override val isNsfw = false

    private val client = OkHttpClient()

    private suspend fun get(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val response = client.newCall(request).execute()
        Jsoup.parse(response.body?.string() ?: "")
    }

    override suspend fun getHomePage(): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val doc = get("$baseUrl/movies/")
        doc.select(".movie").forEach { el ->
            val title = el.select("h2").text().trim()
            val url = el.select("a").attr("href")
            val poster = el.select("img").attr("src")
            if (title.isNotBlank() && url.isNotBlank()) {
                results.add(SearchResult(id = url, title = title, posterUrl = poster.ifBlank { null }, year = null, type = ContentType.TV_SERIES, sourceId = id))
            }
        }
        return results
    }

    override suspend fun getHomePageSections(): List<HomePageSection> {
        val sections = listOf(
            "Film: Ultimi aggiunti" to "$baseUrl/movies/",
            "Serie TV: Ultime aggiunte" to "$baseUrl/serie-tv/",
            "Animazione" to "$baseUrl/serie-tv-generi/animazione/",
            "Azione e Avventura" to "$baseUrl/serie-tv-generi/action-adventure/",
            "Commedia" to "$baseUrl/serie-tv-generi/commedia/",
            "Crime" to "$baseUrl/serie-tv-generi/crime/",
            "Dramma" to "$baseUrl/serie-tv-generi/dramma/",
            "Fantascienza" to "$baseUrl/serie-tv-generi/sci-fi-fantasy/",
            "Horror" to "$baseUrl/film-generi/horror/",
            "Thriller" to "$baseUrl/film-generi/thriller/",
        )

        return sections.mapNotNull { (name, url) ->
            try {
                val doc = get(url)
                val items = doc.select(".movie, .uagb-post__inner-wrap").map { el ->
                    val title = when {
                        el.select("h2").text().isNotBlank() -> el.select("h2").text().trim()
                        else -> el.select(".uagb-post__title > a").text().trim()
                    }
                    val itemUrl = el.select("a").attr("href")
                    val poster = el.select("img").attr("src").ifBlank { null }
                    SearchResult(id = itemUrl, title = title, posterUrl = poster, year = null, type = ContentType.TV_SERIES, sourceId = id)
                }
                if (items.isNotEmpty()) HomePageSection(name = name, items = items.take(15)) else null
            } catch (_: Exception) { null }
        }
    }

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val doc = get("$baseUrl/?s=$query")
        return doc.select(".movie").map { el ->
            val title = el.select("h2").text().trim()
            val url = el.select("a").attr("href")
            val poster = el.select("img").attr("src")
            SearchResult(id = url, title = title, posterUrl = poster.ifBlank { null }, year = null, type = ContentType.TV_SERIES, sourceId = id)
        }
    }

    override suspend fun getDetails(url: String): MediaDetails {
        val doc = get(url)
        val dati = doc.selectFirst(".headingder") ?: throw Exception("No data found")

        val poster = dati.select(".imgs > img").attr("src").replace(Regex("""-\d+x\d+"""), "")
        val title = dati.select(".dataplus > div:nth-child(1) > h1").text().trim()
            .replace(Regex("""\d{4}$"""), "")
        val rating = dati.select(".stars > span:nth-child(3)").text().trim().removeSuffix("/10")
        val genres = dati.select(".stars > span:nth-child(6) > i:nth-child(1)").text().trim()
        val year = dati.select(".stars > span:nth-child(8) > i:nth-child(1)").text().trim()
        val isMovie = url.contains("/film/")

        val episodes = if (!isMovie) {
            getEpisodesFromDoc(doc)
        } else null

        return MediaDetails(
            id = url,
            title = title,
            description = doc.select(".dataplus p").text().trim().ifBlank { null },
            posterUrl = poster.ifBlank { null },
            backdropUrl = poster.ifBlank { null },
            year = year.toIntOrNull(),
            rating = try { rating.toFloat() } catch (_: Exception) { null },
            genres = genres.split(",").map { it.trim() }.filter { it.isNotBlank() },
            type = if (isMovie) ContentType.MOVIE else ContentType.TV_SERIES,
            episodes = episodes
        )
    }

    override suspend fun getEpisodes(url: String): List<Episode> {
        val doc = get(url)
        return getEpisodesFromDoc(doc)
    }

    private fun getEpisodesFromDoc(page: Document): List<Episode> {
        val table = page.selectFirst("#hostlinks > table:nth-child(1)") ?: return emptyList()
        var season: Int? = 1
        return table.select("tr").mapNotNull { row ->
            if (row.childrenSize() == 0) null
            else if (row.childrenSize() == 1) {
                val seasonText = row.select("td:nth-child(1)").text().substringBefore("- Episodi disponibi")
                season = Regex("""\d+""").find(seasonText)?.value?.toInt()
                null
            } else {
                val epTitle = row.select("td:nth-child(1)").text()
                val links = row.select("a").map { it.attr("href") }.joinToString(",")
                Episode(
                    id = links,
                    title = epTitle,
                    season = season,
                    episode = epTitle.substringAfter("x").substringBefore(" ").toIntOrNull(),
                    thumbnailUrl = null,
                    url = links
                )
            }
        }
    }

    override suspend fun getStreamLinks(url: String): List<StreamLink> {
        val links = url.split(",").filter { it.isNotBlank() }.filter { it.contains("uprot") }
        if (links.isEmpty()) return emptyList()

        for (link in links) {
            try {
                val streamUrl = extractFromUprot(link)
                if (streamUrl != null) {
                    return listOf(StreamLink(
                        url = streamUrl,
                        quality = "720p",
                        headers = mapOf(
                            "Referer" to "https://uprot.net/",
                            "User-Agent" to "Mozilla/5.0"
                        )
                    ))
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private suspend fun extractFromUprot(uprotUrl: String): String? = withContext(Dispatchers.IO) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
        val req = Request.Builder().url(uprotUrl).header("User-Agent", ua).header("Referer", baseUrl).build()
        val resp = client.newCall(req).execute()
        val finalUrl = resp.request.url.toString()
        val html = resp.body?.string() ?: return@withContext null

        // Try finding any M3U8 directly
        val m3u8 = Regex("""([^"'\s]+\.m3u8[^"'\s]*)""").find(html)?.value
        if (m3u8 != null && m3u8.startsWith("http")) return@withContext m3u8

        // Try maxstream redirect
        if (finalUrl.contains("maxstream.video")) {
            return@withContext extractMaxStream(finalUrl)
        }

        // Try iframe
        val iframe = Regex("""<iframe[^>]+src="([^"]+)""").find(html)?.groupValues?.get(1)
        if (iframe != null) {
            val iReq = Request.Builder().url(iframe).header("User-Agent", ua).header("Referer", uprotUrl).build()
            val iResp = client.newCall(iReq).execute()
            val iHtml = iResp.body?.string() ?: return@withContext null
            val iUrl = iResp.request.url.toString()

            val m = Regex("""(?:file|src):\s*"([^"]+\.m3u8[^"]*)""").find(iHtml)?.groupValues?.get(1)
            if (m != null) return@withContext m

            if (iUrl.contains("maxstream")) return@withContext extractMaxStream(iUrl)
        }

        null
    }

    private suspend fun extractMaxStream(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")
            .header("Referer", "https://maxstream.video/")
            .build()

        val response = client.newCall(request).execute()
        val finalUrl = response.request.url.toString()
        val html = response.body?.string() ?: return@withContext null

        val m3u8Match = Regex("""src:\s*"([^"]+master\.m3u8[^"]*)""").find(html)
        if (m3u8Match != null) return@withContext m3u8Match.groupValues[1]

        m3u8Match ?: Regex("""(?:file|src):\s*"([^"]+\.m3u8[^"]*)""").find(html)?.groupValues?.get(1)
    }
}
