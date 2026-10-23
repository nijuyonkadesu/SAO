package one.njk.sao.floating

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import javax.inject.Provider

class AllCapabilitiesStateTest {
    private class Configuration : SourceConfiguration {
        override val kind="fixture"
        override val identity="configuration"
    }
    private class Source(private val owner: SourceProfile) : ManagedMediaSource {
        val status=MutableStateFlow<SourceCapabilitiesState>(SourceCapabilitiesState.Loading)
        var discoveries=0
        override val capabilitiesState=flow { discoveries++; emitAll(status) }
        override val identity=flowOf(owner.identity)
        override suspend fun owns(item: MediaItem)=item.remote?.sourceId==owner.identity
        override suspend fun page(query: MediaQuery,cursor: String?): MediaPage=error("Unused")
        override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview)->T): T = block(error("Unused"))
        override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,block: suspend (MediaHandle)->T): T=error("Unused")
        override fun close()=Unit
    }

    @Test fun waitsForEveryMemberAndKeepsFailedReadinessWithHealthyBrowsingFeatures(): Unit=runBlocking {
        val profiles=List(3) { SourceProfile(UUID.randomUUID().toString(),"Source $it",Configuration()) }
        val sources=profiles.associate { it.id to Source(it) }
        val factory=object : MediaSourceFactory { override fun create(profile: SourceProfile)=sources.getValue(profile.id) }
        val dispatchers=FloatingDispatchers(Dispatchers.Unconfined,Dispatchers.Unconfined,Dispatchers.Unconfined,
            Dispatchers.Unconfined,Dispatchers.Unconfined,Dispatchers.Unconfined)
        val all=AllMediaSource(SourceProfile(ALL_SOURCE_ID,"All",AllSourceConfiguration(profiles)),
            mapOf("fixture" to Provider<MediaSourceFactory> { factory }),dispatchers)
        val statuses=mutableListOf<SourceCapabilitiesState>()
        val observing=launch(start=CoroutineStart.UNDISPATCHED) { all.capabilitiesState.collect { statuses+=it } }
        val healthy=SourceCapabilities(setOf(SearchMode.CONTEXT,SearchMode.DESCRIPTION),
            setOf(SearchFacet.MEDIA,SearchFacet.PEOPLE),random=true,sequence=true,
            categories=listOf(SourceCategory("all","All")))
        val second=healthy.copy(modes=setOf(SearchMode.DESCRIPTION),facets=setOf(SearchFacet.MEDIA,SearchFacet.PLACES))
        try {
            sources.getValue(profiles[0].id).status.value=SourceCapabilitiesState.Ready(healthy)
            sources.getValue(profiles[1].id).status.value=SourceCapabilitiesState.Ready(second)
            repeat(3) { yield() }
            assertTrue(statuses.all { it == SourceCapabilitiesState.Loading })
            val error=IllegalStateException("Unavailable")
            sources.getValue(profiles[2].id).status.value=SourceCapabilitiesState.Failed(error)
            val failed=withTimeout(2000) { all.capabilitiesState.first { it is SourceCapabilitiesState.Failed } } as SourceCapabilitiesState.Failed
            assertSame(error,failed.error)
            assertEquals(setOf(SearchMode.DESCRIPTION),failed.available.modes)
            assertEquals(setOf(SearchFacet.MEDIA),failed.available.facets)
            assertTrue(failed.available.random); assertTrue(failed.available.sequence)
            assertEquals(healthy.categories,failed.available.categories)
            assertEquals(failed.available,all.capabilities.first())
            assertTrue(sources.values.all { it.discoveries==1 })
            sources.getValue(profiles[2].id).status.value=SourceCapabilitiesState.Ready(healthy.copy(random=false))
            val ready=withTimeout(2000) { all.capabilitiesState.first { it is SourceCapabilitiesState.Ready } } as SourceCapabilitiesState.Ready
            assertEquals(setOf(SearchMode.DESCRIPTION),ready.value.modes)
            assertFalse(ready.value.random)
            assertTrue(sources.values.all { it.discoveries==1 })
        } finally { observing.cancelAndJoin(); all.close() }
    }
}
