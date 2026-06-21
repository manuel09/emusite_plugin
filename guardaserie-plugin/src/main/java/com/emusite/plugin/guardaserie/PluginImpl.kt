package com.emusite.plugin.guardaserie

import com.emusite.api.Plugin
import com.emusite.api.Source

class PluginImpl : Plugin {
    override val id = "guardaserie"
    override val name = "GuardaSerie"
    override val version = "1.0.0"
    override val description = "Serie TV italiane da GuardaSerie con streaming VixSrc"
    override val language = "it"
    override val iconUrl: String? = null
    override fun getSources(): List<Source> = listOf(GuardaSerieSource())
}
