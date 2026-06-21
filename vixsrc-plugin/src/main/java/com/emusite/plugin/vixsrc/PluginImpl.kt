package com.emusite.plugin.vixsrc

import com.emusite.api.Plugin
import com.emusite.api.Source

class PluginImpl : Plugin {
    override val id = "vixsrc"
    override val name = "VixSrc"
    override val version = "1.0.0"
    override val description = "Cinema e Serie TV via VixSrc - streaming in italiano basato su TMDB"
    override val language = "it"
    override val iconUrl: String? = null

    override fun getSources(): List<Source> = listOf(VixSrcSource())
}
