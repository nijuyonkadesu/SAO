package one.njk.sao.source

import org.junit.Assert.*
import org.junit.Test

class SourceCapabilitiesTest {
    @Test fun unsupportedFacetsAreRemovedWithoutRewritingSearchText() {
        val capabilities=SourceCapabilities(setOf(SearchMode.FILENAME),defaultMode=SearchMode.FILENAME)
        val query=MediaQuery("happy 😂",filter="😂",mode=SearchMode.CONTEXT,randomRequest="random",
            constraints=SearchConstraints("profile",listOf(SearchPerson("person","Name","profile")),"Country","State","City",SearchMedia.VIDEOS))
        val normalized=capabilities.normalize(query)
        assertEquals("happy 😂",normalized.text)
        assertEquals(SearchMode.FILENAME,normalized.mode)
        assertNull(normalized.filter); assertNull(normalized.randomRequest)
        assertTrue(normalized.constraints.people.isEmpty()); assertNull(normalized.constraints.country)
        assertNull(normalized.constraints.state); assertNull(normalized.constraints.city)
        assertEquals(SearchMedia.ALL,normalized.constraints.media)
    }
    @Test fun supportedCapabilitiesPreserveRequestExactly() {
        val capabilities=SourceCapabilities(SearchMode.entries.toSet(),SearchFacet.entries.toSet(),random=true,sequence=true)
        val query=MediaQuery("👩🏽‍💻 skill",filter="😂",mode=SearchMode.OCR,randomRequest="request")
        assertEquals(query,capabilities.normalize(query))
    }
    @Test fun renamedProfilesKeepIdentityButRevisionChangesDoNot() {
        class Configuration(override val identity: String): SourceConfiguration { override val kind="extension" }
        val profile=SourceProfile("00000000-0000-0000-0000-000000000001","Home",Configuration("revision1"))
        assertEquals(profile.identity,profile.copy(name="Renamed").identity)
        assertNotEquals(profile.identity,profile.copy(configuration=Configuration("revision2")).identity)
    }
}
