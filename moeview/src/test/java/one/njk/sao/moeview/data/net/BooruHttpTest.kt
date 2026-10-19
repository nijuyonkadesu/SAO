package one.njk.sao.moeview.data.net

import one.njk.sao.moeview.BooruApi
import one.njk.sao.moeview.BooruConfig
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.ByteRange
import one.njk.sao.moeview.data.MediaKind
import one.njk.sao.moeview.data.MediaType
import one.njk.sao.moeview.data.Post
import one.njk.sao.moeview.data.Rating
import one.njk.sao.moeview.data.SfwMode
import one.njk.sao.moeview.data.TagCategory
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end transport behavior with a local HTTP server:
 *  - edge-first boards hit moeview's edge URL first;
 *  - on a hard failure the other transport is tried (self-healing);
 *  - direct-only boards never leave the direct origin;
 *  - video media prefers the direct CDN and carries the hotlink Referer;
 *  - media responses are streamed with Range honored (206).
 */
class BooruHttpTest {

    private val server = MockWebServer()
    private lateinit var api: BooruApi

    @Before
    fun setUp() {
        server.start()
        // Rewrite every request to the local mock, keeping path + query + headers.
        val rewrite = Interceptor { chain ->
            val url = chain.request().url
            val local =
                url.newBuilder()
                    .scheme("http")
                    .host("127.0.0.1")
                    .port(server.port)
                    .build()
            chain.proceed(chain.request().newBuilder().url(local).build())
        }
        val jsonClient = OkHttpClient.Builder().addInterceptor(rewrite).build()
        val mediaClient = OkHttpClient.Builder().addInterceptor(rewrite).build()
        val config =
            BooruConfig(
                minRequestIntervalMs = 0,
                maxRetriesPerTransport = 0,
                retryBaseDelayMs = 1,
                retryMaxDelayMs = 10,
            )
        api = BooruApi(BooruHttp(config, jsonClient, mediaClient))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `create factory builds a default client`() {
        val created = BooruApi.create()
        assertEquals(SfwMode.SFW, created.config.defaultSfwMode)
        created.close()
    }
    @Test fun autocompleteUsesEachBoardWildcardWhileExactTagLookupDoesNot(): Unit=runBlocking {
        for(board in Board.entries) {
            val body=if(board==Board.GELBOORU) """{"tag":[]}""" else "[]"
            server.enqueue(MockResponse().setBody(body)); api.tags(board,"blue",limit=12)
            val key=when(board) { Board.GELBOORU -> "name_pattern"; Board.DANBOORU -> "search[name_matches]"; else -> "name" }
            assertEquals("blue"+if(board==Board.GELBOORU) "%" else "*",server.takeRequest().requestUrl!!.queryParameter(key))
            server.enqueue(MockResponse().setBody(body)); api.tag(board,"blue_hair")
            assertEquals("blue_hair",server.takeRequest().requestUrl!!.queryParameter(key))
        }
    }

    private fun konachanJson(id: Int) =
        """[{"id":$id,"tags":"blue_archive foxgirl","rating":"q","score":1,"file_url":"https://konachan.net/image/x/konachan-$id.png","preview_url":"https://konachan.net/preview/x/t.jpg"}]"""

    private fun gelbooruJson(id: Int) =
        """{"post":[{"id":$id,"rating":"safe","tags":"blue_archive","file_url":"https://img4.gelbooru.com/images/a/b/c.jpeg","preview_url":"https://img4.gelbooru.com/thumbnails/a/b/t.jpeg"}]}"""

    private fun gelbooruPost(
        fileUrl: String,
        previewUrl: String = "https://img4.gelbooru.com/thumbnails/a/b/t.jpg",
        mediaType: MediaType = MediaType.IMAGE,
    ) = Post(
        id = 1,
        rating = Rating.EXPLICIT,
        rawRating = "explicit",
        tags = emptyList(),
        tagsRaw = "",
        score = 0,
        fileUrl = fileUrl,
        previewUrl = previewUrl,
        mediaType = mediaType,
    )

    // ------------------------------------------------------------------ API ---

    @Test
    fun `edge-first transport is used for konachan`() = runBlocking {
        server.enqueue(MockResponse().setBody(konachanJson(408423)))
        val posts = api.search(Board.KONACHAN, tags = "foxgirl")
        assertEquals(1, posts.size)
        assertEquals(408423, posts[0].id)
        assertEquals(1, server.requestCount)
        val path = server.takeRequest().path ?: ""
        assertTrue("expected edge URL, got $path", path.startsWith("/edge/konachan/"))
    }

    @Test
    fun `falls back to direct when edge fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        server.enqueue(MockResponse().setBody(konachanJson(408424)))
        val posts = api.search(Board.KONACHAN, tags = "foxgirl")
        assertEquals(408424, posts[0].id)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().path ?: ""
        val second = server.takeRequest().path ?: ""
        assertTrue("expected edge first, got $first", first.startsWith("/edge/konachan/"))
        assertTrue("expected direct fallback, got $second", second.startsWith("/post.json"))
    }

    @Test
    fun `edge-first used for gelbooru and tags endpoint works`() = runBlocking {
        server.enqueue(MockResponse().setBody(gelbooruJson(15060588)))
        val posts = api.search(Board.GELBOORU, tags = "blue_archive")
        assertEquals(15060588, posts[0].id)

        server.enqueue(
            MockResponse().setBody("""{"tag":[{"id":7,"name":"blue_archive","count":463436,"type":3}]}"""),
        )
        val tags = api.tags(Board.GELBOORU, prefix = "blue")
        assertEquals("blue_archive", tags[0].name)

        server.takeRequest()
        val tagRequest = server.takeRequest().path ?: ""
        assertTrue("got $tagRequest", tagRequest.startsWith("/edge/gelbooru/"))
    }

    @Test
    fun `danbooru is direct-only`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":4000000,"rating":"s","tag_string":"1girl","file_url":"https://cdn.donmai.us/f.png","preview_file_url":"https://cdn.donmai.us/p.jpg","large_file_url":"https://cdn.donmai.us/l.png"}]""",
            ),
        )
        val posts = api.search(Board.DANBOORU, tags = "1girl")
        assertEquals(4000000, posts[0].id)
        val path = server.takeRequest().path ?: ""
        assertTrue("got $path", path.startsWith("/posts.json"))
    }

    // ------------------------------------------------------------ Rating filter ---

    @Test
    fun `default search is SFW-only`() = runBlocking {
        server.enqueue(MockResponse().setBody(konachanJson(1)))
        api.search(Board.KONACHAN, tags = "foxgirl") // note: no sfwMode -> config default
        val tagsParam = server.takeRequest().requestUrl?.queryParameter("tags") ?: ""
        assertEquals("foxgirl rating:s", tagsParam)
    }

    @Test
    fun `NSFW search negates the safe rating`() = runBlocking {
        server.enqueue(MockResponse().setBody(gelbooruJson(2)))
        api.search(Board.GELBOORU, tags = "blue_archive", sfwMode = SfwMode.NSFW)
        val tagsParam = server.takeRequest().requestUrl?.queryParameter("tags") ?: ""
        assertEquals("blue_archive -rating:general", tagsParam)
    }

    @Test
    fun `ALL mode and gelbooru SFW token`() = runBlocking {
        server.enqueue(MockResponse().setBody(gelbooruJson(3)))
        api.search(Board.GELBOORU, tags = "blue_archive", sfwMode = SfwMode.ALL)
        val all = server.takeRequest().requestUrl?.queryParameter("tags") ?: ""
        assertEquals("blue_archive", all)

        server.enqueue(MockResponse().setBody(gelbooruJson(4)))
        api.search(Board.GELBOORU, tags = "blue_archive", sfwMode = SfwMode.SFW)
        val sfw = server.takeRequest().requestUrl?.queryParameter("tags") ?: ""
        assertEquals("blue_archive rating:general", sfw)
    }

    @Test
    fun `explicit rating term in tags skips the sfw filter`() = runBlocking {
        server.enqueue(MockResponse().setBody(gelbooruJson(5)))
        api.search(Board.GELBOORU, tags = "blue_archive rating:explicit")
        val tagsParam = server.takeRequest().requestUrl?.queryParameter("tags") ?: ""
        assertEquals("blue_archive rating:explicit", tagsParam)
    }

    @Test
    fun `danbooru sfw token is g`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":6,"rating":"g","tag_string":"1girl"}]"""))
        api.search(Board.DANBOORU, tags = "1girl", sfwMode = SfwMode.SFW)
        val tagsParam = server.takeRequest().requestUrl?.queryParameter("tags") ?: ""
        assertEquals("1girl rating:g", tagsParam)
    }

    @Test
    fun `konachan NSFW skips the safe-only edge and goes direct`() = runBlocking {
        server.enqueue(MockResponse().setBody(konachanJson(7)))
        api.search(Board.KONACHAN, tags = "foxgirl", sfwMode = SfwMode.NSFW)
        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue("expected direct, got $path", path.startsWith("/post.json"))
        assertEquals("foxgirl -rating:s", recorded.requestUrl?.queryParameter("tags"))
    }

    @Test
    fun `konachan SFW still uses the edge`() = runBlocking {
        server.enqueue(MockResponse().setBody(konachanJson(8)))
        api.search(Board.KONACHAN, tags = "foxgirl", sfwMode = SfwMode.SFW)
        val path = server.takeRequest().path ?: ""
        assertTrue("expected edge, got $path", path.startsWith("/edge/konachan/"))
    }

    // ----------------------------------------------------------------- Tag search ---

    @Test
    fun `tag search filters by category and blocklist`() = runBlocking {
        val tagBody =
            """{"tag":[
                {"id":1,"name":"blue_archive","count":1000,"type":3},
                {"id":2,"name":"asuna","count":500,"type":4},
                {"id":3,"name":"another","count":200,"type":0},
                {"id":4,"name":"loli","count":9999,"type":0}
            ]}"""

        server.enqueue(MockResponse().setBody(tagBody))
        val characters =
            api.tags(Board.GELBOORU, "b", categories = setOf(TagCategory.CHARACTER))
        assertEquals(listOf("asuna"), characters.map { it.name })

        server.enqueue(MockResponse().setBody(tagBody))
        val excluded =
            api.tags(Board.GELBOORU, "b", categories = setOf(TagCategory.CHARACTER), exclude = setOf("asuna"))
        assertTrue("expected no results, got $excluded", excluded.isEmpty())
    }

    // ---------------------------------------------------------------- Media ---

    @Test
    fun `video media resolves to direct CDN with referer and seeking`() {
        val post =
            gelbooruPost(
                fileUrl = "https://img4.gelbooru.com/images/9e/2f/9e2f.mp4",
                mediaType = MediaType.VIDEO,
            )
        val request = api.mediaRequest(Board.GELBOORU, post)!!
        assertTrue("expected direct CDN, got ${request.url}", request.url.startsWith("https://img4.gelbooru.com/"))
        assertTrue(request.supportsSeeking)
        assertEquals("https://gelbooru.com/", request.headers["Referer"])
    }

    @Test
    fun `image media prefers the hotlink-safe edge`() {
        val post = gelbooruPost(fileUrl = "https://img4.gelbooru.com/images/a/b/c.jpg")
        val request = api.mediaRequest(Board.GELBOORU, post)!!
        assertTrue("expected edge, got ${request.url}", request.url.startsWith("https://moeview.app/edge/gelbooru-img/"))
        assertEquals(false, request.supportsSeeking)
    }

    @Test
    fun `video streams from direct CDN and honors Range`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes 0-9/100")
                .setBody("0123456789"),
        )
        val post =
            gelbooruPost(
                fileUrl = "https://img4.gelbooru.com/images/9e/2f/9e2f.mp4",
                mediaType = MediaType.VIDEO,
            )

        api.openMedia(Board.GELBOORU, post, MediaKind.FULL, ByteRange(0, 9)).use { media ->
            assertEquals(206, media.statusCode)
            assertTrue(media.rangeHonored)
            assertEquals("video/mp4", media.contentType)
            assertEquals("0123456789", media.byteStream().bufferedReader().readText())
        }

        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue("expected direct mp4, got $path", path.endsWith(".mp4"))
        assertEquals("bytes=0-9", recorded.getHeader("Range"))
        assertEquals("https://gelbooru.com/", recorded.getHeader("Referer"))
    }

    @Test
    fun `image streams from the edge without a referer`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/gif")
                .setBody("GIF89a"),
        )
        val post =
            gelbooruPost(
                fileUrl = "https://img4.gelbooru.com/images/50/69/x.gif",
                mediaType = MediaType.GIF,
            )

        api.openMedia(Board.GELBOORU, post, MediaKind.FULL).use { media ->
            assertEquals("image/gif", media.contentType)
        }

        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue("expected edge, got $path", path.startsWith("/edge/gelbooru-img/"))
    }

    @Test
    fun `video thumbnail preview uses the edge as an image`() {
        val post =
            gelbooruPost(
                fileUrl = "https://img4.gelbooru.com/images/9e/2f/9e2f.mp4",
                previewUrl = "https://img4.gelbooru.com/thumbnails/9e/2f/t.jpg",
                mediaType = MediaType.VIDEO,
            )
        val request = api.mediaRequest(Board.GELBOORU, post, MediaKind.PREVIEW)!!
        assertTrue("expected edge, got ${request.url}", request.url.startsWith("https://moeview.app/edge/gelbooru-img/"))
        assertEquals(MediaType.IMAGE, request.mediaType)
    }
    @Test fun cancellationInterruptsBodyReadsAndDoesNotRetry(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(64_000)).throttleBody(1,1,java.util.concurrent.TimeUnit.SECONDS))
        val start=System.nanoTime()
        try { kotlinx.coroutines.withTimeout(300) { api.search(Board.YANDERE) }; org.junit.Assert.fail("Expected cancellation") }
        catch(_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertTrue("Socket cancellation should finish promptly",(System.nanoTime()-start)/1_000_000<3000)
        assertEquals(1,server.requestCount)
    }
    @Test fun rejectedRequestsPreserveServerCooldownWithoutClientRetries(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","120"))
        try { api.search(Board.YANDERE); org.junit.Assert.fail("Expected rejection") }
        catch(error: NoTransportException) {
            assertEquals(429,(error.cause as HttpStatusException).code)
            assertEquals("120",(error.cause as HttpStatusException).retryAfter)
        }
        assertEquals(1,server.requestCount)
    }
}
