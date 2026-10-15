package one.njk.sao.floating

import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import com.google.android.material.chip.Chip
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import org.junit.Assert.*
import org.junit.Test

class TelegramClipboardTest {
    @get:org.junit.Rule val marquees = PickerMarqueeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    @org.junit.Before fun resetSession() {
        EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java).browseSession().reset()
    }
    private fun await(scenario: ActivityScenario<PickerActivity>, condition: (PickerActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000; var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = condition(it) }; SystemClock.sleep(30)
        }
        assertTrue("Picker must settle", ready)
    }
    @Test fun nagramStickerModeCopiesRealWebpAndMediaModeStillCopiesJpeg() = runBlocking {
        val preferences = FloatingPreferences(app); val previous = preferences.current().preferStickers
        preferences.preferStickers(true)
        try {
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "nu.gpu.nagramx", null)).use { scenario ->
                await(scenario) { it.findViewById<Chip>(R.id.prefer_stickers).text == it.getString(R.string.picker_sticker) }
                val item = LocalMediaSource(app).all().first { !it.animated }
                scenario.onActivity { ViewModelProvider(it)[PickerViewModel::class.java].select(item, MessagingTarget.TELEGRAM) }
                await(scenario) { it.findViewById<TextView>(R.id.selection_status).text.toString() == it.getString(R.string.picker_telegram_sticker_copied) }
                scenario.onActivity { activity ->
                    val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip!!
                    assertTrue(clip.description.hasMimeType("image/webp")); assertTrue(clip.getItemAt(0).uri.toString().endsWith(".webp"))
                    activity.contentResolver.openInputStream(clip.getItemAt(0).uri)!!.use { stream ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, bounds)
                        assertEquals("image/webp", bounds.outMimeType); assertEquals(512, bounds.outWidth); assertEquals(512, bounds.outHeight)
                    }
                    activity.findViewById<Chip>(R.id.prefer_stickers).performClick()
                }
                await(scenario) { it.findViewById<Chip>(R.id.prefer_stickers).text == it.getString(R.string.picker_media) }
                scenario.onActivity { it.findViewById<android.view.View>(R.id.copy_action).performClick() }
                await(scenario) { it.getSystemService(ClipboardManager::class.java).primaryClipDescription?.hasMimeType("image/jpeg") == true }
            }
        } finally { preferences.preferStickers(previous) }
    }
    @Test fun togglingModeWithLongTitlePreservesClipboardAndCarouselGeometry() = runBlocking { verifyToggleGeometry() }
    @Test fun largeTextModeTogglePreservesClipboardAndCarouselGeometry() = runBlocking {
        val original = android.provider.Settings.System.getFloat(app.contentResolver, android.provider.Settings.System.FONT_SCALE, 1f)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        shell("settings put system font_scale 2.0")
        try { verifyToggleGeometry() } finally { shell("settings put system font_scale $original") }
    }
    private suspend fun verifyToggleGeometry() {
        val preferences = FloatingPreferences(app); val previous = preferences.current().preferStickers
        preferences.preferStickers(true)
        try {
            val item = LocalMediaSource(app).all().first { !it.animated }.copy(title = "Long filename ".repeat(80))
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "nu.gpu.nagramx", null)).use { scenario ->
                await(scenario) { it.findViewById<Chip>(R.id.prefer_stickers).text == it.getString(R.string.picker_sticker) }
                scenario.onActivity { ViewModelProvider(it)[PickerViewModel::class.java].select(item, MessagingTarget.TELEGRAM) }
                await(scenario) { it.findViewById<TextView>(R.id.selection_status).text.toString() == it.getString(R.string.picker_telegram_sticker_copied) }
                SystemClock.sleep(100)
                var stamp = 0L; var uri = ""; var chipWidth = 0; var carouselHeight = 0; var footerHeight = 0
                scenario.onActivity {
                    val clip = it.getSystemService(ClipboardManager::class.java).primaryClip!!
                    stamp = clip.description.timestamp; uri = clip.getItemAt(0).uri.toString()
                    chipWidth = it.findViewById<Chip>(R.id.prefer_stickers).width
                    carouselHeight = it.findViewById<android.view.View>(R.id.carousel_area).height
                    footerHeight = it.findViewById<android.view.View>(R.id.selection_row).height
                }
                for (label in listOf(R.string.picker_media, R.string.picker_sticker, R.string.picker_media)) {
                    scenario.onActivity { it.findViewById<Chip>(R.id.prefer_stickers).performClick() }
                    await(scenario) { it.findViewById<Chip>(R.id.prefer_stickers).text == it.getString(label) }
                    SystemClock.sleep(100)
                    scenario.onActivity {
                        val clip = it.getSystemService(ClipboardManager::class.java).primaryClip!!
                        assertEquals(stamp, clip.description.timestamp); assertEquals(uri, clip.getItemAt(0).uri.toString())
                        assertEquals(chipWidth, it.findViewById<Chip>(R.id.prefer_stickers).width)
                        assertEquals(carouselHeight, it.findViewById<android.view.View>(R.id.carousel_area).height)
                        assertEquals(footerHeight, it.findViewById<android.view.View>(R.id.selection_row).height)
                        assertEquals(1, it.findViewById<TextView>(R.id.selection_title).lineCount)
                        assertEquals(1, it.findViewById<TextView>(R.id.selection_status).lineCount)
                        assertEquals(it.getString(R.string.picker_telegram_sticker_copied), it.findViewById<TextView>(R.id.selection_status).text.toString())
                    }
                }
            }
        } finally { preferences.preferStickers(previous) }
    }
}
