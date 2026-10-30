package one.njk.sao.floating

import android.graphics.Color
import android.os.SystemClock
import android.view.TextureView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SoftwarePreviewDeviceTest {
    @Test fun oddWidthPreviewRendersAndCancelsItsOwnedWorkers(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = File.createTempFile("software-preview-", ".bin", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("odd-mpeg4.mp4").use { from -> input.outputStream().use { from.copyTo(it) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            ActivityScenario.launch(MediaPlaybackFixtureActivity::class.java).use { scenario ->
                val shown = CompletableDeferred<Unit>()
                lateinit var playback: Job
                scenario.onActivity { activity -> playback = scope.launch {
                    SoftwareVideoPreview.play(activity.binding.videoPreview, input) { shown.complete(Unit) }
                } }
                withTimeout(10_000) { shown.await() }
                var red = false
                val deadline = SystemClock.elapsedRealtime() + 3_000
                while (!red && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val texture = activity.binding.videoPreview.videoSurfaceView as TextureView
                        texture.getBitmap(8, 8)?.let { bitmap ->
                            try { val color = bitmap.getPixel(4, 4); red = Color.red(color) > 220 && Color.green(color) < 40 && Color.blue(color) < 40 }
                            finally { bitmap.recycle() }
                        }
                    }
                    if (!red) delay(30)
                }
                assertTrue("The actual TextureView must contain the decoded red frame", red)
                withTimeout(3_000) { playback.cancelAndJoin() }
                assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "SAO software preview" })
            }
        } finally { scope.cancel(); input.delete() }
    }
}
