package com.emusite.plugin.onlineserietv

import com.emusite.api.Plugin
import com.emusite.api.Source

class PluginImpl : Plugin {
    override val id = "onlineserietv"
    override val name = "OnlineSerieTV"
    override val version = "1.0.0"
    override val description = "Film e Serie TV in italiano da OnlineSerieTV"
    override val language = "it"
    override val iconUrl: String? = null

    override fun getSources(): List<Source> = listOf(OnlineSerieTVSource())
}
