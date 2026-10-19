package one.njk.sao.moeview

import one.njk.sao.moeview.data.ApiKind
import one.njk.sao.moeview.data.Board
import one.njk.sao.moeview.data.MediaKind
import one.njk.sao.moeview.data.MediaType
import one.njk.sao.moeview.data.Post
import one.njk.sao.moeview.data.Rating
import one.njk.sao.moeview.data.dto.parsePosts
import one.njk.sao.moeview.data.dto.parseTags
import one.njk.sao.moeview.data.net.BooruHttp
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parsing of real board JSON shapes into the unified models, plus the
 * image-URL rewrite rules. No network involved.
 */
class ParseSamplesTest {

    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

    @Test
    fun konachanPostParses() {
        val body =
            """
            [{
              "id": 408423,
              "tags": "animal_ears bell blush bow collar foxgirl loli miko original pink_hair",
              "rating": "q",
              "score": 20,
              "md5": "0a39fbcdd3b1f361a82d7575263691c0",
              "file_url": "https://konachan.net/image/0a39fbcdd3b1f361a82d7575263691c0/Konachan%20-%20408423.png",
              "preview_url": "https://konachan.net/data/preview/0a/39/0a39fbcdd3b1f361a82d7575263691c0.jpg",
              "sample_url": "https://konachan.net/sample/0a/39/0a39fbcdd3b1f361a82d7575263691c0/Konachan%20sample.jpg",
              "width": 1160,
              "height": 1400,
              "file_ext": "png",
              "source": "https://www.pixiv.net/en/artworks/149641584",
              "created_at": 1789315160
            }]
            """.trimIndent()

        val posts = parsePosts(ApiKind.MOEBOORU, body, json)
        assertEquals(1, posts.size)
        val p = posts[0]
        assertEquals(408423, p.id)
        assertEquals(Rating.QUESTIONABLE, p.rating)
        assertEquals(listOf("animal_ears", "bell", "blush"), p.tags.take(3))
        assertEquals("https://konachan.net/image/0a39fbcdd3b1f361a82d7575263691c0/Konachan%20-%20408423.png", p.fileUrl)
        assertEquals(1160, p.width)
        assertEquals("png", p.fileExt)
        assertEquals(1789315160L, p.createdAt)
    }

    @Test
    fun gelbooruEnvelopeParses() {
        val body =
            """
            {"post": [{
              "id": 15060588,
              "rating": "explicit",
              "tags": "1girl absurdres after_sex blue_archive loli",
              "score": 42,
              "md5": "1f03bb9d38940287b6642d24132bb274",
              "file_url": "https://img4.gelbooru.com/images/1f/03/1f03bb9d38940287b6642d24132bb274.jpeg",
              "preview_url": "https://img4.gelbooru.com/thumbnails/1f/03/thumbnail_1f03bb9d38940287b6642d24132bb274.jpg",
              "sample_url": "https://img4.gelbooru.com/samples/1f/03/sample_1f03bb9d38940287b6642d24132bb274.jpg",
              "width": 2118,
              "height": 3161
            }]}
            """.trimIndent()

        val posts = parsePosts(ApiKind.GELBOORU, body, json)
        val p = posts.first()
        assertEquals(15060588, p.id)
        assertEquals(Rating.EXPLICIT, p.rating)
        assertEquals("blue_archive", p.tags[3])
        assertEquals(2118, p.width)
    }

    @Test
    fun gelbooruTagsParse() {
        val body =
            """{"@attributes":{"limit":2},"tag":[{"id":1037340,"name":"blue_archive","count":463436,"type":3,"ambiguous":0}]}"""
        val tags = parseTags(ApiKind.GELBOORU, body, json)
        assertEquals(1, tags.size)
        assertEquals("blue_archive", tags[0].name)
        assertEquals(463436, tags[0].count)
        assertEquals("copyright", tags[0].category.label)
    }

    @Test
    fun danbooruParses() {
        // created_at is an ISO-8601 string on Danbooru -> coerced to null via default.
        val body =
            """
            [{
              "id": 4000000,
              "created_at": "2024-01-01T00:00:00.000-05:00",
              "score": 5,
              "rating": "s",
              "tag_string": "1girl blue_archive uniform",
              "file_url": "https://cdn.donmai.us/original/ab/cd/abc.png",
              "large_file_url": "https://cdn.donmai.us/sample/ab/cd/sample-abc.png",
              "preview_file_url": "https://cdn.donmai.us/preview/ab/cd/abc.jpg",
              "image_width": 1280,
              "image_height": 1920,
              "source": "pixiv",
              "file_ext": "png"
            }]
            """.trimIndent()

        val posts = parsePosts(ApiKind.DANBOORU, body, json)
        val p = posts.first()
        assertEquals(4000000, p.id)
        // Danbooru "s" = SENSITIVE (unlike moebooru/Gelbooru where it means safe).
        assertEquals(Rating.SENSITIVE, p.rating)
        assertEquals("https://cdn.donmai.us/sample/ab/cd/sample-abc.png", p.sampleUrl)
        assertEquals(1280, p.width)
        // ISO-8601 "2024-01-01T00:00:00.000-05:00" == 2024-01-01T05:00:00Z -> epoch seconds
        assertEquals(1704085200L, p.createdAt)
    }

    @Test
    fun ratingMapsEveryBoardConvention() {
        // Generic fallback (apiKind = null).
        assertEquals(Rating.SAFE, Rating.from("s"))
        assertEquals(Rating.SAFE, Rating.from("safe"))
        assertEquals(Rating.GENERAL, Rating.from("general"))
        assertEquals(Rating.SENSITIVE, Rating.from("sensitive"))
        assertEquals(Rating.QUESTIONABLE, Rating.from("q"))
        assertEquals(Rating.EXPLICIT, Rating.from("e"))
        assertEquals(Rating.UNKNOWN, Rating.from(""))
        assertEquals(Rating.UNKNOWN, Rating.from(null))

        // Board-aware: moebooru "s" = SAFE, Danbooru "s" = SENSITIVE.
        assertEquals(Rating.SAFE, Rating.from("s", ApiKind.MOEBOORU))
        assertEquals(Rating.SENSITIVE, Rating.from("s", ApiKind.DANBOORU))
        assertEquals(Rating.SAFE, Rating.from("safe", ApiKind.GELBOORU))
        assertEquals(Rating.GENERAL, Rating.from("general", ApiKind.GELBOORU))
        // Moebooru sources never produce sensitive; q/e are the two NSFW levels.
        assertEquals(Rating.QUESTIONABLE, Rating.from("q", ApiKind.MOEBOORU))
        assertEquals(Rating.EXPLICIT, Rating.from("e", ApiKind.MOEBOORU))
    }

    @Test
    fun gelbooruImagesRouteThroughEdge() {
        val api = BooruApi(BooruHttp(BooruConfig()))
        val post =
            Post(
                id = 15060588,
                rating = Rating.EXPLICIT,
                rawRating = "explicit",
                tags = emptyList(),
                tagsRaw = "",
                score = 0,
                fileUrl = "https://img4.gelbooru.com/images/1f/03/1f03bb9d38940287b6642d24132bb274.jpeg",
                sampleUrl = "https://img4.gelbooru.com/samples/1f/03/sample_1f03bb9d38940287b6642d24132bb274.jpg",
                previewUrl =
                    "https://img4.gelbooru.com/thumbnails/1f/03/thumbnail_1f03bb9d38940287b6642d24132bb274.jpg",
            )

        assertEquals(
            "https://moeview.app/edge/gelbooru-img/images/1f/03/1f03bb9d38940287b6642d24132bb274.jpeg",
            api.mediaUrl(Board.GELBOORU, post, MediaKind.FULL),
        )
        assertEquals(
            "https://moeview.app/edge/gelbooru-img/samples/1f/03/sample_1f03bb9d38940287b6642d24132bb274.jpg",
            api.mediaUrl(Board.GELBOORU, post, MediaKind.SAMPLE),
        )
        assertEquals(
            "https://moeview.app/edge/gelbooru-img/thumbnails/1f/03/thumbnail_1f03bb9d38940287b6642d24132bb274.jpg",
            api.mediaUrl(Board.GELBOORU, post, MediaKind.PREVIEW),
        )
    }

    @Test
    fun nonGelbooruImagesStayDirect() {
        val api = BooruApi(BooruHttp(BooruConfig()))
        val post =
            Post(
                id = 408423,
                rating = Rating.QUESTIONABLE,
                rawRating = "q",
                tags = emptyList(),
                tagsRaw = "",
                score = 0,
                fileUrl = "https://konachan.net/image/0a39fbcdd3b1f361a82d7575263691c0/Konachan.png",
                previewUrl = "https://konachan.net/data/preview/0a/39/thumb.jpg",
            )
        assertEquals(
            "https://konachan.net/image/0a39fbcdd3b1f361a82d7575263691c0/Konachan.png",
            api.mediaUrl(Board.KONACHAN, post, MediaKind.FULL),
        )
        // Konachan has no sample: falls back to file_url for SAMPLE.
        assertEquals(
            "https://konachan.net/image/0a39fbcdd3b1f361a82d7575263691c0/Konachan.png",
            api.mediaUrl(Board.KONACHAN, post, MediaKind.SAMPLE),
        )
    }

    @Test
    fun yandeReTagCategoryMaps() {
        val body =
            """[{"id":186895,"name":"blue_archive","count":47809,"type":3,"ambiguous":false}]"""
        val tags = parseTags(ApiKind.MOEBOORU, body, json)
        assertEquals("blue_archive", tags[0].name)
        assertEquals("copyright", tags[0].category.label)
    }

    @Test
    fun mediaTypeDetection() {
        assertEquals(MediaType.VIDEO, MediaType.fromExtension("mp4"))
        assertEquals(MediaType.VIDEO, MediaType.fromExtension(".webm"))
        assertEquals(MediaType.GIF, MediaType.fromExtension("gif"))
        assertEquals(MediaType.IMAGE, MediaType.fromExtension("jpg"))
        assertEquals(MediaType.IMAGE, MediaType.fromExtension(null))
        // Gelbooru gives no file_ext, so detection falls back to the URL.
        assertEquals(
            MediaType.VIDEO,
            MediaType.detect(null, "https://img4.gelbooru.com/images/9e/2f/9e2f.mp4"),
        )
        assertEquals(
            MediaType.GIF,
            MediaType.detect(null, "https://img4.gelbooru.com/images/50/69/x.gif?v=1"),
        )
    }

    @Test
    fun gelbooruVideoPostDetected() {
        val body =
            """{"post":[{"id":1,"rating":"explicit","tags":"video","file_url":"https://img4.gelbooru.com/images/9e/2f/9e2f.mp4","preview_url":"https://img4.gelbooru.com/thumbnails/9e/2f/t.jpg","sample_url":"","width":640,"height":480}]}"""
        val post = parsePosts(ApiKind.GELBOORU, body, json).first()
        assertEquals(MediaType.VIDEO, post.mediaType)
        assertEquals(true, post.isVideo)
    }

    @Test
    fun gelbooruGifPostDetected() {
        val body =
            """{"post":[{"id":2,"rating":"safe","tags":"animated","file_url":"https://img4.gelbooru.com/images/50/69/x.gif","preview_url":"https://img4.gelbooru.com/thumbnails/50/69/t.jpg","width":640,"height":480}]}"""
        val post = parsePosts(ApiKind.GELBOORU, body, json).first()
        assertEquals(MediaType.GIF, post.mediaType)
        assertEquals(false, post.isVideo)
    }

    @Test
    fun danbooruVideoDurationParsed() {
        val body =
            """[{"id":9,"rating":"e","tag_string":"video","file_url":"https://cdn.donmai.us/original/ab/cd/abcd.webm","file_ext":"webm","duration":12.5,"large_file_url":"https://cdn.donmai.us/sample/ab/cd/abcd.webm","preview_file_url":"https://cdn.donmai.us/preview/ab/cd/abcd.jpg"}]"""
        val post = parsePosts(ApiKind.DANBOORU, body, json).first()
        assertEquals(MediaType.VIDEO, post.mediaType)
        assertEquals(12.5, post.durationSeconds!!, 0.0)
    }
    @Test fun gelbooruAcceptsItsLiveTextTimestamp() {
        val posts=parsePosts(ApiKind.GELBOORU,"""{"post":[{"id":1,"created_at":"Sun Oct 18 04:00:35 -0500 2026"}]}""",json)
        assertEquals(java.time.OffsetDateTime.parse("2026-10-18T09:00:35Z").toEpochSecond(),posts.single().createdAt)
        assertEquals(123L,parsePosts(ApiKind.GELBOORU,"""{"post":[{"id":2,"created_at":123}]}""",json).single().createdAt)
    }
    @Test fun danbooruAcceptsIsoDatesWithoutMilliseconds() {
        assertEquals(123L,one.njk.sao.moeview.data.dto.parseIso8601("1970-01-01T00:02:03Z"))
    }
}