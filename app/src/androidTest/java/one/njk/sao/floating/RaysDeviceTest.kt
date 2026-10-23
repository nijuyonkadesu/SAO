package one.njk.sao.floating

import one.njk.sao.source.*

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import one.njk.sao.rays.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RaysDeviceTest {
    private fun response(next: Int? = null, uri: String = "content://com.skyd.rays.fileprovider/Sticker/id") = JSONObject()
        .put("startIndex", 0).put("size", 1).put("nextIndex", next)
        .put("data", JSONArray().put(JSONObject().put("uri", uri).put("mimeType", "image/jpg")
            .put("sticker", JSONObject().put("uuid", "id").put("title", "amazing 👋").put("stickerMd5", "revision"))
            .put("tags", JSONArray().put(JSONObject().put("tag", "👋")))))
    @Test fun pagedContractPreservesUnicodeMimeAndEndOfResults() {
        val page = RaysClient.parse(response().toString(), RaysClient.PACKAGE, 0, 60)
        assertNull(page.nextIndex)
        assertEquals("amazing 👋", page.items.single().title)
        assertEquals(listOf("👋"), page.items.single().tags)
        assertEquals("image/jpeg", page.items.single().mimeType)
        assertEquals(1, RaysClient.parse(response(1).toString(), RaysClient.PACKAGE, 0, 60).nextIndex)
    }
    @Test fun invalidOffsetsAndUntrustedUrisAreRejected() {
        listOf(response(0), response(2), response(uri = "file:///data/private"),
            response(uri = "content://unrelated.provider/secret"), response().put("size", 2),
            response().put("startIndex", 1), response().put("data", JSONArray())).forEach { json ->
            try { RaysClient.parse(json.toString(), RaysClient.PACKAGE, 0, 60); fail("Accepted invalid Rays response") }
            catch (expected: RaysException) { assertEquals(RaysProblem.INVALID_RESPONSE, expected.problem) }
        }
    }
    @Test fun raysProfilesPersistAndKeepProviderSeparation(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java).sources()
        val previous = profiles.current()
        try {
            val profile = SourceProfile(UUID.randomUUID().toString(), "Offline stickers", RaysConfig(RaysClient.DEBUG_PACKAGE))
            profiles.upsert(profile, true)
            val fresh = SourceProfiles(app, FloatingDispatchers(), mapOf("extension" to ExtensionConfigurationCodec(), "rays" to RaysConfigurationCodec()))
            assertEquals(profile, fresh.current().selected)
            val rays=RaysSourceFactory(app, FloatingDispatchers(), SourceMediaCache(app)).create(profile)
            try {
                val capabilities=rays.capabilities.first()
                assertFalse(capabilities.searchOptions)
                assertFalse(capabilities.random)
                assertFalse(capabilities.sequence)
            } finally { rays.close() }
        } finally { profiles.restore(previous) }
    }
}
