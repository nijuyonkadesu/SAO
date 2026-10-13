package one.njk.sao.floating

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.njk.sao.immich.ImmichClient
import one.njk.sao.immich.SmartSearch
import one.njk.sao.immich.SearchFilter
import one.njk.sao.immich.EnumFilter
import one.njk.sao.immich.DateEqualityFilter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Read-only opt-in using an explicitly supplied app-private credential fixture. */
class LiveImmichPaginationTest {
    @Test fun realContextSearchContinuesBeyondFirstPageAndPreservesRank(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveImmich") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(app.filesDir, "live_immich.json")
        assumeTrue("A private connection fixture is required", input.isFile)
        val config = JSONObject(input.readText()).let { ImmichConfig(it.getString("url"), it.getString("key")) }
        val connection = requireNotNull(config)
        val report = JSONObject().put("server_writes", false); val queries = JSONArray()
        try {
            ImmichClient(connection.connection).use { reference ->
                report.put("server_version", reference.api.version().toString())
                ImmichMediaSource(app, connection, "live-pagination:${connection.identity}", FloatingDispatchers()).use { source ->
                    for (query in listOf("skill", "GIF", "😂")) {
                        val expected = reference.api.smartSearch(SmartSearch(query, SearchFilter(
                            type = EnumFilter(`in` = listOf("IMAGE", "VIDEO")), visibility = EnumFilter(`in` = listOf("timeline", "archive")),
                            trashedAt = DateEqualityFilter(null)), size = 300)).assets.items.map { it.id }
                        assertTrue("Reference search must contain more than one hundred results", expected.size > 100)
                        var cursor: String? = null; val ids = mutableListOf<String>(); var pages = 0
                        do {
                            val page = source.page(MediaQuery(query), cursor); ids += page.items.map { it.remote!!.assetId }
                            cursor = page.nextCursor; pages++
                        } while (pages < 3 && cursor != null)
                        assertTrue("Context pagination must pass the old cutoff", ids.size > 100)
                        val count = minOf(expected.size, ids.size)
                        assertTrue("Paged Context must preserve the reference ranked media", expected.take(count) == ids.take(count))
                        assertEquals("Pages must not overlap", ids.size, ids.toSet().size)
                        queries.put(JSONObject().put("query", query).put("loaded", ids.size).put("pages", pages)
                            .put("ranked_prefix_matches", true).put("has_more", cursor != null))
                    }
                }
            }
            report.put("queries", queries).put("success", true)
        } finally { File(app.filesDir, "live_pagination_report.json").writeText(report.toString(2)); input.delete() }
    }
}
