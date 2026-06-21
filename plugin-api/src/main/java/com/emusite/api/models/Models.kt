package com.emusite.api.models

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val type: ContentType,
    val sourceId: String
)

@Serializable
data class MediaDetails(
    val id: String,
    val title: String,
    val description: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val rating: Float?,
    val genres: List<String>,
    val type: ContentType,
    val episodes: List<Episode>?
)

@Serializable
data class Episode(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val thumbnailUrl: String?,
    val url: String
)

@Serializable
data class StreamLink(
    val url: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList()
)

@Serializable
data class Subtitle(
    val url: String,
    val language: String,
    val label: String = language
)

@Serializable
enum class ContentType {
    MOVIE,
    TV_SERIES,
    ANIME
}

@Serializable
data class HomePageSection(
    val name: String,
    val items: List<SearchResult>
)
