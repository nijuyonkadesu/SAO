package one.njk.sao.source

import org.junit.Assert.*
import org.junit.Test

class MediaIdentityTest {
    private fun item(owner: String, asset: String, revision: String) = MediaItem(
        revision,"Title","","image/png",emptyList(),emptyList(),
        remote=RemoteMedia(owner,asset,false,cacheKeys=mapOf("preview" to revision)))

    @Test fun equalProviderIdsRemainDistinctAcrossOwnersAndStableAcrossRevisions() {
        val first=item("account-one","shared-asset","same-provider-id")
        val second=item("account-two","shared-asset","same-provider-id")
        val revised=item("account-one","shared-asset","new-provider-id")
        assertNotEquals(first.identityKey,second.identityKey)
        assertEquals(first.identityKey,revised.identityKey)
        assertEquals(2,listOf(first,second,revised).distinctBy { it.identityKey }.size)
    }

    @Test fun savedStateRoundTripsOpaqueUnicodeAndDelimiterContainingIds() {
        val identity=MediaIdentity("owner:.／👩🏽‍💻","asset\u0000:/?.é")
        assertEquals(identity,MediaIdentity.decode(identity.encode()))
        assertEquals(identity.encode(),MediaIdentity.decode(identity.encode())!!.encode())
        assertNotEquals(MediaIdentity("a:b","c").encode(),MediaIdentity("a","b:c").encode())
    }

    @Test fun malformedNoncanonicalAndOversizedSavedStateIsIgnored() {
        listOf(null,"legacy-provider-id","a.b.c","%%.YQ","YQ==.Yg","_w.YQ","x".repeat(16*1024+1))
            .forEach { assertNull(it,MediaIdentity.decode(it)) }
    }
}
