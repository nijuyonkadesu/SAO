package one.njk.sao.source

import org.json.JSONObject
import java.util.UUID

/** Configurations are values; provider implementations are shared by kind, never by account. */
interface SourceConfiguration {
    val kind: String
    val identity: String
}

data class SourceProfile(val id: String, val name: String, val configuration: SourceConfiguration, val nsfw: Boolean = false) {
    init { UUID.fromString(id); require(name.isNotBlank()) }
    val identity get() = "$id:${configuration.identity}"
    override fun toString() = "SourceProfile(id=$id, name=$name, kind=${configuration.kind})"
}

data class SourceCatalog(val profiles: List<SourceProfile> = emptyList(), val selectedId: String? = null) {
    init {
        require(profiles.map { it.id }.distinct().size == profiles.size)
        require(selectedId == null || profiles.any { it.id == selectedId })
    }
    val selected get() = profiles.firstOrNull { it.id == selectedId }
    val identity get() = selected?.identity ?: LOCAL_SOURCE_ID
}

interface SourceConfigurationCodec {
    fun encode(configuration: SourceConfiguration): JSONObject
    fun decode(json: JSONObject): SourceConfiguration
}

interface MediaSourceFactory {
    fun create(profile: SourceProfile): ManagedMediaSource
}

interface ManagedMediaSource : MediaSource, AutoCloseable

/** Preserve settings for a provider removed by an older build, without activating it. */
class UnknownSourceConfiguration(override val kind: String, val json: String) : SourceConfiguration {
    override val identity = digest(json)
    override fun toString() = "UnknownSourceConfiguration(kind=$kind)"
}
