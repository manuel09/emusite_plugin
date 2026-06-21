package com.emusite.plugin.vixsrc

import com.emusite.api.Source
import com.emusite.api.models.ContentType
import com.emusite.api.models.Episode
import com.emusite.api.models.HomePageSection
import com.emusite.api.models.MediaDetails
import com.emusite.api.models.SearchResult
import com.emusite.api.models.StreamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

class VixSrcSource : Source {
    override val id = "vixsrc_movies"
    override val name = "VixSrc"
    override val baseUrl = "https://vixsrc.to"
    override val type = ContentType.MOVIE
    override val language = "it"
    override val isNsfw = false

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getHomePage(): List<SearchResult> {
        val trending = TMDB.getTrendingMovies()
        val popular = TMDB.getPopularMovies()
        return (trending.results + popular.results).distinctBy { it.id }.map { it.toSearchResult(MOVIE, id) }
    }

    override suspend fun getHomePageSections(): List<HomePageSection> {
        val trending = TMDB.getTrendingMovies()
        val popular = TMDB.getPopularMovies()

        return listOf(
            HomePageSection(
                name = "Trending",
                items = trending.results.map { it.toSearchResult(MOVIE, id) }.take(15)
            ),
            HomePageSection(
                name = "Popular",
                items = popular.results.map { it.toSearchResult(MOVIE, id) }.take(15)
            )
        )
    }

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val movies = TMDB.searchMovies(query, page)
        val tvs = TMDB.searchTv(query, page)
        return (movies.results.map { it.toSearchResult(MOVIE, id) } +
                tvs.results.map { it.toSearchResult(TV, id) })
    }

    override suspend fun getDetails(url: String): MediaDetails {
        val parts = url.split(":")
        val tmdbType = parts[0]
        val tmdbId = parts[1].toInt()

        return if (tmdbType == "movie") {
            val movie = TMDB.getMovieDetails(tmdbId)
            MediaDetails(
                id = url,
                title = movie.title,
                description = movie.overview,
                posterUrl = movie.posterUrl,
                backdropUrl = movie.backdropPath?.let { "$TMDB.IMAGE_BASE_ORIGINAL$it" },
                year = movie.year,
                rating = movie.voteAverage,
                genres = movie.genres.map { it.name },
                type = ContentType.MOVIE,
                episodes = null
            )
        } else {
            val tv = TMDB.getTvDetails(tmdbId)
            MediaDetails(
                id = url,
                title = tv.name,
                description = tv.overview,
                posterUrl = tv.posterUrl,
                backdropUrl = tv.backdropPath?.let { "$TMDB.IMAGE_BASE_ORIGINAL$it" },
                year = tv.year,
                rating = tv.voteAverage,
                genres = tv.genres.map { it.name },
                type = ContentType.TV_SERIES,
                episodes = null
            )
        }
    }

    override suspend fun getEpisodes(url: String): List<Episode> {
        val parts = url.split(":")
        val tmdbType = parts[0]
        if (tmdbType != "tv") return emptyList()

        val tmdbId = parts[1].toInt()
        val tv = TMDB.getTvDetails(tmdbId)
        return tv.seasons?.take(10)?.flatMap { season ->
            try {
                val seasonDetail = TMDB.getTvSeason(tmdbId, season.seasonNumber)
                seasonDetail.episodes.map { ep ->
                    val epId = "tv:$tmdbId:${season.seasonNumber}:${ep.episodeNumber}"
                    Episode(
                        id = epId,
                        title = ep.name,
                        season = season.seasonNumber,
                        episode = ep.episodeNumber,
                        thumbnailUrl = ep.stillPath?.let { "$TMDB.IMAGE_BASE$it" },
                        url = epId
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    override suspend fun getStreamLinks(url: String): List<StreamLink> {
        val parts = url.split(":")
        val tmdbType = parts[0]
        val tmdbId = parts[1].toInt()

        val apiUrl = if (tmdbType == "movie") {
            "$baseUrl/api/movie/$tmdbId"
        } else {
            val season = parts.getOrElse(2) { "1" }.toInt()
            val episode = parts.getOrElse(3) { "1" }.toInt()
            "$baseUrl/api/tv/$tmdbId/$season/$episode"
        }
        return extractVixSrcStreams(apiUrl)
    }

    private suspend fun extractVixSrcStreams(apiUrl: String): List<StreamLink> {
        return withContext(Dispatchers.IO) {
        try {
            val apiRequest = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")
                .build()

            val apiResponse = client.newCall(apiRequest).execute()
            val apiBody = apiResponse.body?.string() ?: return@withContext emptyList()
            val apiData = json.decodeFromString<VixApiResponse>(apiBody)
            val embedPath = apiData.src

            if (embedPath.isNullOrBlank()) return@withContext emptyList()

            val embedUrl = if (embedPath.startsWith("http")) embedPath else "$baseUrl$embedPath"
            val embedRequest = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", baseUrl)
                .build()

            val embedResponse = client.newCall(embedRequest).execute()
            val html = embedResponse.body?.string() ?: return@withContext emptyList()

            val playlist = extractMasterPlaylist(html) ?: return@withContext emptyList()
            val activeStreamUrl = extractActiveStreamUrl(html) ?: playlist.url
            val params = playlist.params
            val token = params.token
            val expires = params.expires

            var finalUrl = activeStreamUrl
            if (!finalUrl.contains("?")) {
                finalUrl = "$finalUrl?token=$token&expires=$expires"
            } else {
                finalUrl = "$finalUrl&token=$token&expires=$expires"
            }
            if (playlist.canPlayFHD) {
                finalUrl += "&h=1"
            }

            listOf(
                StreamLink(
                    url = finalUrl,
                    quality = if (playlist.canPlayFHD) "1080p" else "720p",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
                        "Referer" to baseUrl,
                        "Origin" to baseUrl
                    )
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
        }
    }

    private fun extractActiveStreamUrl(html: String): String? {
        val regex = Regex("""window\.streams\s*=\s*(\[[^\]]*\])""")
        val match = regex.find(html) ?: return null
        return try {
            val streamsJson = match.groupValues[1].replace("\\/", "/")
            val streams = json.decodeFromString<List<VixStream>>(streamsJson)
            streams.firstOrNull { it.active }?.url
        } catch (_: Exception) { null }
    }

    private fun extractMasterPlaylist(html: String): VixPlaylist? {
        val scriptRegex = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.MULTILINE)
        val matches = scriptRegex.findAll(html)

        for (match in matches) {
            val script = match.groupValues[1]
            if (script.contains("masterPlaylist")) {
                return try {
                    val jsObj = extractJsObject(script)
                    val root = json.decodeFromString<JsonObject>(jsObj)
                    val playlist = json.decodeFromString<VixPlaylist>(
                        root["masterPlaylist"]?.toString() ?: "{}"
                    )
                    val canPlayFHD = root["canPlayFHD"]?.let {
                        try { json.decodeFromString<Boolean>(it.toString()) } catch (_: Exception) { false }
                    } ?: false
                    playlist.copy(canPlayFHD = canPlayFHD)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }
        return null
    }

    private fun extractJsObject(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()
            "\"$key\": $cleaned"
        }

        return "{\n${jsonObjects.joinToString(",\n")}\n}".replace("'", "\"")
    }

    companion object {
        const val MOVIE = "movie"
        const val TV = "tv"

        fun TmdbItem.toSearchResult(tmdbType: String, sourceId: String): SearchResult {
            return SearchResult(
                id = "$tmdbType:${this.id}",
                title = this.displayTitle,
                posterUrl = this.posterUrl,
                year = this.year,
                type = when (tmdbType) {
                    "movie" -> ContentType.MOVIE
                    else -> ContentType.TV_SERIES
                },
                sourceId = sourceId
            )
        }
    }
}

@Serializable
data class VixStream(
    val name: String = "",
    val active: Boolean = false,
    val url: String = ""
)

@Serializable
data class VixApiResponse(
    val src: String? = null
)

@kotlinx.serialization.Serializable
data class VixPlaylistParams(
    val token: String = "",
    val expires: String = ""
)

@kotlinx.serialization.Serializable
data class VixPlaylist(
    val url: String = "",
    val params: VixPlaylistParams = VixPlaylistParams(),
    val canPlayFHD: Boolean = false
)
