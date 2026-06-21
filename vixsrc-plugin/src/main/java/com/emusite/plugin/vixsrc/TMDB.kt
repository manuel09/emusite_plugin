package com.emusite.plugin.vixsrc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object TMDB {
    private const val API_KEY = "4ecab3ee8d35bfdfe11585929ee55d44"
    private const val BASE_URL = "https://api.themoviedb.org/3"
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    const val IMAGE_BASE_ORIGINAL = "https://image.tmdb.org/t/p/original"

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchMovies(query: String, page: Int = 1): TmdbSearchResponse {
        return get("$BASE_URL/search/movie", mapOf("query" to query, "page" to page.toString()))
    }

    suspend fun searchTv(query: String, page: Int = 1): TmdbSearchResponse {
        return get("$BASE_URL/search/tv", mapOf("query" to query, "page" to page.toString()))
    }

    suspend fun getTrendingMovies(page: Int = 1): TmdbSearchResponse {
        return get("$BASE_URL/trending/movie/week", mapOf("page" to page.toString()))
    }

    suspend fun getTrendingTv(page: Int = 1): TmdbSearchResponse {
        return get("$BASE_URL/trending/tv/week", mapOf("page" to page.toString()))
    }

    suspend fun getPopularMovies(page: Int = 1): TmdbSearchResponse {
        return get("$BASE_URL/movie/popular", mapOf("page" to page.toString()))
    }

    suspend fun getPopularTv(page: Int = 1): TmdbSearchResponse {
        return get("$BASE_URL/tv/popular", mapOf("page" to page.toString()))
    }

    suspend fun getMovieDetails(movieId: Int): TmdbMovieDetail {
        return get("$BASE_URL/movie/$movieId")
    }

    suspend fun getTvDetails(tvId: Int): TmdbTvDetail {
        return get("$BASE_URL/tv/$tvId")
    }

    suspend fun getTvSeason(tvId: Int, seasonNumber: Int): TmdbSeasonDetail {
        return get("$BASE_URL/tv/$tvId/season/$seasonNumber")
    }

    private suspend inline fun <reified T> get(url: String, params: Map<String, String> = emptyMap()): T {
        val urlBuilder = StringBuilder(url)
        urlBuilder.append("?api_key=$API_KEY")
        params.forEach { (key, value) ->
            urlBuilder.append("&$key=$value")
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("Accept", "application/json")
            .build()

        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IOException("TMDB API error: ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response")
            json.decodeFromString(body)
        }
    }
}

@Serializable
data class TmdbSearchResponse(
    val page: Int = 1,
    val results: List<TmdbItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1
)

@Serializable
data class TmdbItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Float? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("media_type") val mediaType: String? = null
) {
    val displayTitle: String get() = title ?: name ?: "Unknown"
    val year: Int? get() = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
    val posterUrl: String? get() = posterPath?.let { "${TMDB.IMAGE_BASE}$it" }
}

@Serializable
data class TmdbMovieDetail(
    val id: Int = 0,
    val title: String = "",
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Float? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val videos: TmdbVideos? = null
) {
    val year: Int? get() = releaseDate?.substringBefore("-")?.toIntOrNull()
    val posterUrl: String? get() = posterPath?.let { "${TMDB.IMAGE_BASE}$it" }
}

@Serializable
data class TmdbTvDetail(
    val id: Int = 0,
    val name: String = "",
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Float? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    val seasons: List<TmdbSeason>? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList()
) {
    val year: Int? get() = firstAirDate?.substringBefore("-")?.toIntOrNull()
    val posterUrl: String? get() = posterPath?.let { "${TMDB.IMAGE_BASE}$it" }
}

@Serializable
data class TmdbSeason(
    val id: Int = 0,
    val name: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null
)

@Serializable
data class TmdbSeasonDetail(
    val id: Int = 0,
    val name: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<TmdbEpisode> = emptyList()
)

@Serializable
data class TmdbEpisode(
    val id: Int = 0,
    val name: String = "",
    val overview: String? = null,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("vote_average") val voteAverage: Float? = null,
    @SerialName("runtime") val runtime: Int? = null
)

@Serializable
data class TmdbGenre(
    val id: Int = 0,
    val name: String = ""
)

@Serializable
data class TmdbVideos(
    val results: List<TmdbVideo> = emptyList()
)

@Serializable
data class TmdbVideo(
    val key: String = "",
    val site: String = "",
    val type: String = ""
)
