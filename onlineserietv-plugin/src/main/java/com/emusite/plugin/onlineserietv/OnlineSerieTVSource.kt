package com.emusite.plugin.onlineserietv

import android.content.Context
import com.emusite.api.Source
import com.emusite.api.WebViewHelper
import com.emusite.api.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class OnlineSerieTVSource(private val appContext: Context? = null) : Source {
    override val id = "onlineserietv"
    override val name = "OnlineSerieTV"
    override val baseUrl = "https://lingering-truth-455c.diegon7771.workers.dev"
    override val type = ContentType.TV_SERIES
    override val language = "it"
    override val isNsfw = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
        val links = url.split(",").filter { it.isNotBlank() }
        if (links.isEmpty()) return emptyList()

        val ua = mapOf(
            "Referer" to baseUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
        )

        for (link in links) {
            try {
                val streamUrl = when {
                    link.contains("uprot") -> extractFromUprot(link)
                    link.contains("maxstream") -> extractMaxStream(link)
                    link.contains("flexy") -> extractFlexy(link)
                    link.contains("vixcloud") -> extractVixCloud(link)
                    else -> extractGeneric(link)
                }
                if (streamUrl != null) {
                    return listOf(StreamLink(url = streamUrl, quality = "720p", headers = ua))
                }
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    private suspend fun extractGeneric(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0").build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext null
        val finalUrl = resp.request.url.toString()

        Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(body)?.value
            ?: if (finalUrl.contains("maxstream")) extractMaxStream(finalUrl) else null
    }

    private suspend fun extractFlexy(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0").header("Referer", "https://flexy.stream/").build()
        val resp = client.newCall(req).execute()
        Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(resp.body?.string() ?: "")?.value
    }

    private suspend fun extractVixCloud(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0").build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return@withContext null
        Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(body)?.value
    }

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun extractFromUprot(uprotUrl: String): String? = withContext(Dispatchers.IO) {
        // Try WebView first (bypasses Cloudflare, runs JS)
        if (appContext != null) {
            try {
                val html = WebViewHelper.loadPage(appContext!!, uprotUrl)
                val mp = extractMasterPlaylist(html)
                if (mp != null) {
                    val u = mp.url + "?token=" + mp.params.token + "&expires=" + mp.params.expires
                    return@withContext if (mp.canPlayFHD) u + "&h=1" else u
                }
                val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(html)?.value
                if (m3u8 != null) return@withContext m3u8
            } catch (_: Exception) {}
        }

        // Fallback: standard HTTP
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
        var currentUrl = uprotUrl
        for (i in 1..5) {
            val req = Request.Builder().url(currentUrl).header("User-Agent", ua).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: break
            val finalUrl = resp.request.url.toString()

            val mp = extractMasterPlaylist(body)
            if (mp != null) {
                val u = mp.url + "?token=" + mp.params.token + "&expires=" + mp.params.expires
                return@withContext if (mp.canPlayFHD) u + "&h=1" else u
            }
            val m3u8 = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(body)?.value
            if (m3u8 != null) return@withContext m3u8
            if (finalUrl.contains("maxstream.video")) {
                extractMaxStream(finalUrl)?.let { return@withContext it }
            }
            val iframe = Regex("""<iframe[^>]+src="([^"]+)""").find(body)?.groupValues?.get(1)
            if (iframe != null) { currentUrl = iframe; continue }
            break
        }
        null
    }

    private fun extractMasterPlaylist(html: String): VixStylePlaylist? {
        if (!html.contains("masterPlaylist")) return null
        return try {
            val scriptBlocks = Regex("""<script[^>]*>([\s\S]*?)</script>""").findAll(html)
            for (block in scriptBlocks) {
                val script = block.groupValues[1]
                if (!script.contains("masterPlaylist")) continue
                val extracted = extractJsObject(script)
                val root = json.decodeFromString<JsonObject>(extracted)
                val mp = root["masterPlaylist"]?.toString() ?: continue
                val playlist = json.decodeFromString<VixStylePlaylist>(mp)
                val fhd = root["canPlayFHD"]?.let {
                    try { json.decodeFromString<Boolean>(it.toString()) } catch (_: Exception) { false }
                } ?: false
                if (playlist.url.isNotBlank()) return playlist.copy(canPlayFHD = fhd)
            }
            null
        } catch (_: Exception) { null }
    }

    private fun extractJsObject(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""").split(script).drop(1)
        val keys = Regex("""window\.(\w+)\s*=""").findAll(script).map { it.groupValues[1] }.toList()
        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value.replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1").trim()
            "\"$key\": $cleaned"
        }
        return "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
    }

    @Serializable
    data class VixStylePlaylistParams(val token: String = "", val expires: String = "")

    @Serializable
    data class VixStylePlaylist(
        val url: String = "",
        val params: VixStylePlaylistParams = VixStylePlaylistParams(),
        val canPlayFHD: Boolean = false
    )

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
