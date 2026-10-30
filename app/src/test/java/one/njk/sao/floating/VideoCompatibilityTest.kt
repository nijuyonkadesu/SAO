package one.njk.sao.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCompatibilityTest {
    @Test fun keepsWebmWhenSupportedAndFallsBackOnlyWhenMp4IsConfirmed() {
        assertEquals("video/webm", VideoCompatibility.mime("video/webm") { true })
        assertEquals("video/mp4", VideoCompatibility.mime("video/webm") { it == "video/mp4" })
        assertEquals("video/webm", VideoCompatibility.mime("video/webm") { false })
        assertEquals("image/gif", VideoCompatibility.mime("image/gif") { false })
    }
}
