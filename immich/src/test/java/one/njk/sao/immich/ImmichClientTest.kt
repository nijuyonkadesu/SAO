package one.njk.sao.immich

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.nio.file.Files
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class ImmichClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ImmichClient
    private val asset = """{"id":"asset-1","originalFileName":"😂.gif","type":"IMAGE","updatedAt":"2026-10-09T00:00:00Z","unknownFutureField":true}"""
    @Before fun setup() { server = MockWebServer(); server.start(); client = ImmichClient(ImmichConnection(server.url("/photos/").toString(), "test-key")) }
    @After fun close() { server.shutdown() }

    @Test fun baseUrlPreservesProxyPrefixAndDoesNotDoubleApi() {
        assertEquals(server.url("/photos/api/"), client.connection.apiUrl)
        assertEquals(server.url("/photos/api/"), ImmichConnection(server.url("/photos/api/").toString(), "key").apiUrl)
        assertFalse(client.connection.toString().contains("test-key"))
        for (url in listOf("https://user:pass@example.com", "https://example.com/?key=secret", "https://example.com/#fragment")) {
            try { ImmichConnection(url, "key"); fail("Invalid URL accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun metadataSearchPreservesEmojiUsesStructuredFiltersAndCursor() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"assets":{"items":[$asset],"nextCursor":"opaque-next"}}"""))
        val filter = SearchFilter(type = EnumFilter(eq = "IMAGE"), trashedAt = DateEqualityFilter(null), or = listOf(
            SearchBranch(description = StringFilter(like = "%😂%")), SearchBranch(tagIds = IdsFilter(any = listOf("tag-1")))))
        val response = client.api.search(MetadataSearch(filter, "cursor-first"))
        assertEquals("opaque-next", response.assets.nextCursor); assertEquals("😂.gif", response.assets.items.single().originalFileName)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/photos/api/search/metadata", request.path); assertEquals("test-key", request.getHeader("x-api-key"))
        val json = request.body.readUtf8()
        assertTrue(json.contains("😂")); assertTrue(json.contains("\"filter\"")); assertTrue(json.contains("\"cursor\":\"cursor-first\""))
        assertTrue(json.contains("\"trashedAt\":{\"eq\":null}")); assertFalse(json.contains("isTrashed"))
        assertFalse(json.contains("\"albumIds\":null")); assertFalse(json.contains("\"page\""))
    }
    @Test fun contextSearchSerializesPagedShapeWithoutStructuredFields(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("""{"assets":{"items":[],"nextPage":"18","nextCursor":null}}"""))
        val result = client.api.pagedSmartSearch(PagedSmartSearch("happy 😂", page = 17,
            albumIds = listOf("album-one"), tagIds = listOf("tag-one")))
        assertEquals("18", result.assets.nextPage); assertNull(result.assets.nextCursor)
        val request = server.takeRequest(); assertEquals("/photos/api/search/smart", request.path)
        assertEquals("test-key", request.getHeader("x-api-key"))
        val json = request.body.readUtf8()
        assertTrue(json.contains("\"page\":17")); assertTrue(json.contains("happy 😂"))
        assertTrue(json.contains("\"albumIds\":[\"album-one\"]")); assertTrue(json.contains("\"tagIds\":[\"tag-one\"]"))
        assertFalse(json.contains("filter")); assertFalse(json.contains("cursor")); assertFalse(json.contains("visibility"))
        assertTrue(json.contains("\"withDeleted\":false"))
    }
    @Test fun patchesDistinguishClearFromOmittedAndUseCorrectAlbumVerb() = runBlocking {
        server.enqueue(MockResponse().setBody(asset))
        client.updateAsset("asset-1", AssetUpdate(description = "", rating = Change(null)))
        var request = server.takeRequest()!!
        assertEquals("PUT", request.method); assertEquals("{\"description\":\"\",\"rating\":null}", request.body.readUtf8())
        server.enqueue(MockResponse().setBody("""{"id":"album-1","albumName":"Reactions"}"""))
        client.updateAlbum("album-1", AlbumUpdate(description = Change(null)))
        request = server.takeRequest()!!
        assertEquals("PATCH", request.method); assertEquals("{\"description\":null}", request.body.readUtf8())
    }
    @Test fun metadataSearchOmitsUnsetNullableDateFilter() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"assets":{"items":[]}}"""))
        client.api.search(MetadataSearch(SearchFilter(type = EnumFilter(eq = "IMAGE")), size = 1))
        val json = server.takeRequest()!!.body.readUtf8()
        assertFalse(json.contains("trashedAt")); assertFalse(json.contains("null"))
    }
    @Test fun readOnlySearchRecoversWhenAReusedConnectionClosesBeforeResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"search\":true}"))
        client.api.features(); server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"assets":{"items":[$asset]}}"""))
        assertEquals(1, client.api.search(MetadataSearch()).assets.items.size)
        val interrupted = server.takeRequest()!!; val recovered = server.takeRequest()!!
        assertEquals(1, interrupted.sequenceNumber); assertEquals(0, recovered.sequenceNumber)
        assertEquals(interrupted.path, recovered.path)
        assertEquals(interrupted.body.readUtf8(), recovered.body.readUtf8())
        assertEquals("test-key", recovered.getHeader("x-api-key"))
    }
    @Test fun editsNeverRetryAnAmbiguousWriteAfterConnectionFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"search\":true}"))
        client.api.features(); server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(asset))
        try { client.updateAsset("asset-1", AssetUpdate(description = "edited")); fail("Ambiguous write was repeated") }
        catch (_: IOException) { }
        assertEquals("PUT", server.takeRequest()!!.method)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }
    @Test fun randomSearchRecoversDroppedReadConnectionAndUsesAnArrayResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"search\":true}"))
        client.api.features(); server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("[$asset]"))
        val results = client.api.randomSearch(RandomSearch(SearchFilter(trashedAt = DateEqualityFilter(null))))
        assertEquals("😂.gif", results.single().originalFileName)
        val interrupted = server.takeRequest()!!; val recovered = server.takeRequest()!!
        assertEquals(1, interrupted.sequenceNumber); assertEquals(0, recovered.sequenceNumber)
        assertEquals("/photos/api/search/random", recovered.path)
        assertEquals(interrupted.body.readUtf8(), recovered.body.clone().readUtf8())
        assertEquals("test-key", recovered.getHeader("x-api-key"))
        val json = recovered.body.readUtf8()
        assertTrue(json.contains("\"size\":100")); assertTrue(json.contains("\"trashedAt\":{\"eq\":null}"))
        assertFalse(json.contains("cursor")); assertFalse(json.contains("query")); assertFalse(json.contains("orderBy"))
    }
    @Test fun albumMembershipUsesIdsAndHandlesPartialFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"a","success":true},{"id":"b","success":false,"error":"not_found"}]"""))
        val results = client.api.removeFromAlbum("album", BulkIds(listOf("a", "b")))
        val request = server.takeRequest()!!
        assertEquals("DELETE", request.method); assertEquals("/photos/api/albums/album/assets", request.path)
        assertEquals("{\"ids\":[\"a\",\"b\"]}", request.body.readUtf8()); assertFalse(results[1].success)
    }
    @Test fun multipartUploadUsesCurrentContractAndRetainsFileBytes() = runBlocking {
        val file = Files.createTempFile("reaction-", ".gif").toFile()
        try {
            file.writeBytes("GIF89a-test-payload".toByteArray())
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"uploaded","status":"created"}"""))
            val result = client.upload(file, "image/gif", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"))
            val request = server.takeRequest()!!; val body = request.body.readUtf8()
            assertEquals("created", result.status); assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            for (name in listOf("assetData", "fileCreatedAt", "fileModifiedAt", "GIF89a-test-payload")) assertTrue(body.contains(name))
            assertFalse(body.contains("deviceId")); assertFalse(body.contains("deviceAssetId"))
        } finally { file.delete() }
    }
    @Test fun editsSerializeCropRotateAndMirrorWithoutFlatteningOriginal() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"assetId":"a","edits":[]}"""))
        client.api.setEdits("a", AssetEdits(listOf(EditAction.crop(0, 5, 100, 80), EditAction.rotate(90), EditAction.mirror(false))))
        val request = server.takeRequest()!!; val body = request.body.readUtf8()
        assertEquals("/photos/api/assets/a/edits", request.path)
        for (part in listOf("\"action\":\"crop\"", "\"angle\":90", "\"axis\":\"vertical\"")) assertTrue(body.contains(part))
    }
    @Test fun redirectsNeverForwardCredentialsToAnotherServer() = runBlocking {
        val other = MockWebServer(); other.start()
        try {
            other.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/stolen")))
            try { client.api.apiKey(); fail("Redirect followed") } catch (error: HttpException) { assertEquals(302, error.code()) }
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { other.shutdown() }
    }
    @Test fun downloadIsAtomicRetainsBytesAndBoundsChunkedResponses() = runBlocking {
        val directory = Files.createTempDirectory("immich-download").toFile(); val destination = directory.resolve("gif")
        try {
            server.enqueue(MockResponse().setBody("GIF89a-original"))
            client.download("a", destination, edited = false)
            assertEquals("GIF89a-original", destination.readText())
            assertEquals("/photos/api/assets/a/original?edited=false", server.takeRequest()!!.path)
            server.enqueue(MockResponse().setChunkedBody("too-large-file", 2))
            try { client.download("a", destination, maxBytes = 3); fail("Limit ignored") } catch (_: IllegalArgumentException) { }
            assertEquals("GIF89a-original", destination.readText()); assertEquals(listOf("gif"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }
    @Test fun unauthorizedResponsesDoNotBecomeEmptySearchResults() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try { client.api.search(MetadataSearch()); fail("Authentication failure swallowed") }
        catch (error: HttpException) { assertEquals(401, error.code()) }
    }
}
