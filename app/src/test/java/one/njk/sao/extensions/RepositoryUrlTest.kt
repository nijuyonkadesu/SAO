package one.njk.sao.extensions

import org.junit.Assert.*
import org.junit.Test

class RepositoryUrlTest {
    @Test fun githubLinksResolveToTheCatalogWithoutChangingDirectSignedUrls() {
        for(url in listOf("https://github.com/nijuyonkadesu/SAO-extensions","https://github.com/nijuyonkadesu/SAO-extensions.git",
            "https://github.com/nijuyonkadesu/SAO-extensions/tree/repo","https://github.com/nijuyonkadesu/SAO-extensions/blob/repo/index.json"))
            assertEquals(ExtensionCatalog.URL,RepositoryUrl.normalize(url))
        assertEquals("https://example.com/catalog%2Fv1.json?signature=a%26b%2Bc",RepositoryUrl.normalize(" https://EXAMPLE.com:443/catalog%2Fv1.json?signature=a%26b%2Bc "))
        assertEquals("https://example.com:8443/index.json",RepositoryUrl.normalize("https://example.com:8443/index.json"))
    }
    @Test fun credentialsFragmentsInvalidPortsAndNonHttpsAreRejected() {
        for(url in listOf("http://example.com/index.json","file:///tmp/index.json","https://me:secret@example.com/index.json",
            "https://example.com/index.json#part","https://example.com:99999/index.json","not a URL")) {
            assertTrue(url,runCatching { RepositoryUrl.normalize(url) }.isFailure)
        }
    }
    @Test fun progressForAnotherPackageDoesNotInvalidateThisCardsState() {
        val before=CatalogState(activePackage="first",phase=InstallPhase.DOWNLOADING,progress=10)
        val after=before.copy(progress=60)
        assertEquals(before.installRow("second"),after.installRow("second"))
        assertNotEquals(before.installRow("first"),after.installRow("first"))
    }
}
