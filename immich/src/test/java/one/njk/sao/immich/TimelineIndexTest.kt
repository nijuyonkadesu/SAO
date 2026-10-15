package one.njk.sao.immich

import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TimelineIndexTest {
    @Test fun readsOnlyIdsInServerOrderAndToleratesNewFields() = runBlocking {
        val body = """{"createdAt":["later","earlier"],"id":["b","a"],"future":{"nested":[1,2,3]},"thumbhash":[null,null]}""".toResponseBody()
        assertEquals(listOf("b", "a"), TimelineIndex.read(body))
        assertEquals(emptyList<String>(), TimelineIndex.read("""{"id":[]}""".toResponseBody()))
    }
    @Test fun refusesMissingOrDuplicateIdsInsteadOfShowingAnEmptyTimeline() = runBlocking {
        for (json in listOf("{}", """{"id":[],"id":[]}""", """{"id":[null]}""")) {
            try { TimelineIndex.read(json.toResponseBody()); fail("Malformed timeline accepted") }
            catch (_: Exception) { }
        }
    }
    @Test fun cancellationClosesAStalledStreamingBody(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        val client = ImmichClient(ImmichConnection(server.url("/").toString(), "fixture"))
        try {
            server.enqueue(MockResponse().setBody("""{"id":["first","second"]}""").throttleBody(8, 3, TimeUnit.SECONDS))
            val body = client.api.timeBucket("2026-10-01")
            val read = async(Dispatchers.IO) { TimelineIndex.read(body) }
            delay(100)
            withTimeout(2_000) { read.cancelAndJoin() }
            assertTrue(read.isCancelled)
            val request = server.takeRequest()
            assertEquals("takenAt", request.requestUrl!!.queryParameter("orderBy"))
            assertEquals("desc", request.requestUrl!!.queryParameter("order"))
            assertEquals("true", request.requestUrl!!.queryParameter("withStacked"))
        } finally { client.close(); server.shutdown() }
    }
}
