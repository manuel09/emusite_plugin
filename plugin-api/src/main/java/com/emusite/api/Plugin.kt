package com.emusite.api

import android.content.Context
import com.emusite.api.models.*

interface Plugin {
    val id: String
    val name: String
    val version: String
    val description: String
    val language: String
    val iconUrl: String?

    fun getSources(): List<Source>
    fun onInit(context: Context) {}
}

interface Source {
    val id: String
    val name: String
    val baseUrl: String
    val type: ContentType
    val language: String
    val isNsfw: Boolean

    suspend fun search(query: String, page: Int = 1): List<SearchResult>
    suspend fun getDetails(url: String): MediaDetails
    suspend fun getEpisodes(url: String): List<Episode>
    suspend fun getStreamLinks(url: String): List<StreamLink>
    suspend fun getHomePage(): List<SearchResult>
    suspend fun getHomePageSections(): List<HomePageSection> {
        val items = getHomePage()
        return if (items.isEmpty()) emptyList()
        else listOf(HomePageSection(name = "Home", items = items))
    }
}
