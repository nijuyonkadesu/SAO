package one.njk.sao.floating

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import one.njk.sao.immich.ImmichClient
import one.njk.sao.immich.MetadataSearch
import one.njk.sao.immich.SearchFilter
import one.njk.sao.immich.EnumFilter
import retrofit2.HttpException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, read-only test. Supply credentials through app-private stdin, never runner arguments. */
class LiveImmichDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private val report = JSONObject()
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(label, condition())
    }
    private fun await(scenario: ActivityScenario<PickerActivity>, label: String, condition: (PickerActivity) -> Boolean) {
        await(label) { var result = false; scenario.onActivity { result = condition(it) }; result }
    }
    private fun model(activity: PickerActivity) = ViewModelProvider(activity)[PickerViewModel::class.java]
    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else instrumentation.runOnMainSync(block)
    }
    private fun resumedPicker(): PickerActivity? {
        var activity: PickerActivity? = null
        main {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PickerActivity>().singleOrNull()
        }
        return activity
    }
    private fun icon(): FloatingActionButton? {
        var result: FloatingActionButton? = null
        main {
            result = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull()
        }
        return result
    }
    private fun tapRemainingIcon() {
        await("Floating icon must tuck") { FloatingSession.iconState.value == FloatingSession.IconState.TUCKED && icon() != null }
        var x = 0; var y = 0
        instrumentation.runOnMainSync {
            val icon = icon()!!; val location = IntArray(2); icon.getLocationOnScreen(location)
            val left = maxOf(0, location[0]); val right = minOf(app.resources.displayMetrics.widthPixels, location[0] + icon.width)
            assertTrue("Half the icon must remain touchable", right - left in (icon.width / 2 - 3)..(icon.width / 2 + 3))
            assertEquals(.35f, icon.alpha, .03f)
            x = (left + right) / 2; y = location[1] + icon.height / 2
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        await("Icon must open the picker") { resumedPicker() != null && FloatingSession.pickerVisible && icon() == null }
    }

    @Test fun realServerConnectionSearchPagingAndFloatingCopy(): Unit = runBlocking {
        val input = File(app.filesDir, "live_immich.json")
        assumeTrue("Live test requires an explicit flag and app-private credentials",
            InstrumentationRegistry.getArguments().getString("liveImmich") == "true" && input.isFile)
        val credentials = JSONObject(input.readText())
        val url = credentials.getString("url"); val key = credentials.getString("key")
        val settings = access.immichSettings()
        val preferences = FloatingPreferences(app); val oldOptions = preferences.current()
        val started = SystemClock.elapsedRealtime()
        try {
            val probe = ImmichClient(ImmichConfig(url, key).connection).api
            suspend fun <T> probe(path: String, request: suspend () -> T): T = try { request() }
                catch (error: HttpException) {
                    val body = error.response()?.errorBody()?.string()?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val errors = body?.optJSONArray("errors")
                    val paths = (0 until (errors?.length() ?: 0)).map { errors!!.getJSONObject(it).optJSONArray("path").toString() }
                    throw AssertionError("Live $path returned HTTP ${error.code()}, invalid fields: $paths")
                }
            probe("server/version") { probe.version() }
            probe("api-keys/me") { probe.apiKey() }
            probe("server/features") { probe.features() }
            probe("albums") { probe.albums() }
            probe("search/metadata") { probe.search(MetadataSearch(SearchFilter(type = EnumFilter(eq = "IMAGE")), size = 1)) }
            report.put("device_api_preflight", true)
            settings.save(null); access.browseSession().reset()
            ActivityScenario.launch<ImmichSettingsActivity>(Intent(app, ImmichSettingsActivity::class.java)).use { scenario ->
                await("Connection form must load") { var ready = false
                    scenario.onActivity { ready = it.findViewById<View>(R.id.test_connection).isEnabled }; ready }
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.server_url).setText(url)
                    it.findViewById<EditText>(R.id.api_key).setText(key)
                    it.findViewById<View>(R.id.test_connection).performClick()
                }
                await("The emulator must verify the exact server URL") { var ready = false
                    scenario.onActivity { ready = it.findViewById<TextView>(R.id.status).text.toString().startsWith(it.getString(R.string.immich_connected)) }; ready }
                assertNull("Test connection must not save", settings.current())
                scenario.onActivity { it.findViewById<View>(R.id.save_connection).performClick() }
                await("The native form must save the verified connection") { var ready = false
                    scenario.onActivity { ready = it.findViewById<TextView>(R.id.status).text.toString().startsWith(it.getString(R.string.immich_saved)) }; ready }
            }
            val config = settings.current()!!
            assertTrue("Saved credentials must match without printing them", config.serverUrl == url && config.apiKey == key && settings.profiles.current().selectedId != null)
            val encrypted = File(app.noBackupFilesDir, "source-profiles-v1")
            assertFalse("Credentials must be encrypted", encrypted.readBytes().toString(Charsets.UTF_8).contains(key))
            input.delete()
            report.put("native_form_and_encryption", true)
            val api = ImmichClient(config.connection).api
            report.put("server_version", api.version().toString())
            report.put("tags", api.tags().size); report.put("albums", api.albums().size)
            val source = SwitchingMediaSource(app, LocalMediaSource(app), settings, FloatingDispatchers())
            // Exercise the metadata opt-out as well as the new smart-search default.
            settings.save(config.copy(smartSearch = false))
            val first = source.page(MediaQuery())
            assertTrue("Live library must have images", first.items.isNotEmpty())
            report.put("first_page", first.items.size)
            if (first.nextCursor != null) {
                val second = source.page(MediaQuery(), first.nextCursor)
                assertTrue("Cursor page must contain different photos", first.items.map { it.remote!!.assetId }.intersect(second.items.map { it.remote!!.assetId }.toSet()).isEmpty())
                assertTrue(second.items.isNotEmpty()); report.put("second_page", second.items.size)
            }
            val wanted = first.items.first { !it.animated }
            val exact = source.page(MediaQuery(wanted.title))
            assertTrue("Literal filename/description search must find the chosen photo", exact.items.any { it.remote?.assetId == wanted.remote?.assetId })
            report.put("literal_search", true)
            report.put("emoji_results", source.page(MediaQuery("😂")).items.size)
            report.put("emoji_filters", source.filters().size)
            preferences.preferStickers(true)
            access.browseSession().reset()
            var focused: String? = null
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "nu.gpu.nagramx", null)).use { scenario ->
                await(scenario, "Real images must load") { model(it).state.value.let { state -> !state.loading && !state.error && state.items.isNotEmpty() } }
                await(scenario, "Remote thumbnail must decode") {
                    val grid = it.findViewById<RecyclerView>(R.id.media_grid)
                    (grid.findViewHolderForAdapterPosition(0) as? MediaCarouselAdapter.Holder)?.binding?.preview?.drawable is android.graphics.drawable.BitmapDrawable
                }
                scenario.onActivity {
                    assertTrue(it.findViewById<RecyclerView>(R.id.media_grid).findViewHolderForAdapterPosition(0)!!.itemView.performClick())
                }
                await(scenario, "Selecting a live image must copy immediately") { it.findViewById<TextView>(R.id.selection_message).text.toString().startsWith(it.getString(R.string.picker_telegram_sticker_copied)) }
                var stamp = 0L; var clipUri = ""
                scenario.onActivity {
                    val clip = it.getSystemService(ClipboardManager::class.java).primaryClip!!
                    assertTrue(clip.description.hasMimeType("image/webp")); stamp = clip.description.timestamp; clipUri = clip.getItemAt(0).uri.toString()
                    it.contentResolver.openInputStream(clip.getItemAt(0).uri)!!.use { stream ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, bounds)
                        assertEquals("image/webp", bounds.outMimeType); assertEquals(512, bounds.outWidth); assertEquals(512, bounds.outHeight)
                    }
                    it.findViewById<Chip>(R.id.prefer_stickers).performClick()
                }
                await(scenario, "Mode must change without another copy") { it.findViewById<Chip>(R.id.prefer_stickers).text == it.getString(R.string.picker_media) }
                scenario.onActivity {
                    val clip = it.getSystemService(ClipboardManager::class.java).primaryClip!!
                    assertEquals(stamp, clip.description.timestamp); assertEquals(clipUri, clip.getItemAt(0).uri.toString())
                    it.findViewById<View>(R.id.copy_action).performClick()
                }
                await(scenario, "Explicit Media copy must produce JPEG") { it.getSystemService(ClipboardManager::class.java).primaryClipDescription?.hasMimeType("image/jpeg") == true }
                scenario.onActivity { it.findViewById<RecyclerView>(R.id.media_grid).scrollToPosition(50) }
                await(scenario, "Carousel must request the next live page") { model(it).state.value.items.size > first.items.size }
                scenario.onActivity { it.findViewById<RecyclerView>(R.id.media_grid).scrollToPosition(5) }
                await(scenario, "Carousel must settle on a later image") { (it.findViewById<RecyclerView>(R.id.media_grid).adapter as MediaCarouselAdapter).focusedId?.let { id -> id != first.items[0].id } == true }
                SystemClock.sleep(200)
                scenario.onActivity { focused = (it.findViewById<RecyclerView>(R.id.media_grid).adapter as MediaCarouselAdapter).focusedId }
            }
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "nu.gpu.nagramx", null)).use { scenario ->
                await(scenario, "Reopening must preserve carousel position") { (it.findViewById<RecyclerView>(R.id.media_grid).adapter as MediaCarouselAdapter).focusedId == focused }
                scenario.onActivity { it.findViewById<View>(R.id.search_toggle).performClick(); it.findViewById<EditText>(R.id.search).setText("😂") }
                await(scenario, "Emoji input must be searched without an error") { model(it).state.value.let { state -> state.query.text == "😂" && !state.loading && !state.error } }
                scenario.onActivity { it.findViewById<View>(R.id.close).performClick() }
            }
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "nu.gpu.nagramx", null)).use { scenario ->
                await(scenario, "Reopening must retain emoji query") { model(it).query.value.text == "😂" && it.findViewById<EditText>(R.id.search).text.toString() == "😂" }
            }
            report.put("picker_preview_copy_modes_paging_and_reopen", true)
            val gif = source.page(MediaQuery(".gif")).items.firstOrNull { it.mimeType == "image/gif" }
            report.put("gif_available", gif != null)
            if (gif != null) {
                val original = source.preview(gif) as File
                assertTrue("GIF preview must use original GIF bytes", original.inputStream().use { stream ->
                    val header = ByteArray(6); stream.read(header) == 6 && header.toString(Charsets.US_ASCII).startsWith("GIF8") })
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, "nu.gpu.nagramx", null)).use { scenario ->
                    scenario.onActivity { model(it).select(gif, MessagingTarget.TELEGRAM) }
                    await(scenario, "Real GIF must copy without flattening") { it.findViewById<TextView>(R.id.selection_message).text.toString().startsWith(it.getString(R.string.picker_gif_copied)) }
                    scenario.onActivity {
                        val clip = it.getSystemService(ClipboardManager::class.java).primaryClip!!
                        assertTrue(clip.description.hasMimeType("image/gif"))
                        it.contentResolver.openInputStream(clip.getItemAt(0).uri)!!.use { stream ->
                            assertTrue("Exported GIF bytes must remain identical", original.readBytes().contentEquals(stream.readBytes())) }
                    }
                }
                report.put("gif_original_and_clipboard_retained", true)
            }
            if (api.features().smartSearch) {
                settings.save(config.copy(smartSearch = true)); access.browseSession().reset()
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
                    scenario.onActivity { model(it).search("sticker") }
                    await(scenario, "Smart search must complete in the picker") { model(it).state.value.let { state -> state.query.text == "sticker" && !state.loading && !state.error } }
                    scenario.onActivity { report.put("smart_search_results", model(it).state.value.items.size) }
                }
            }
            settings.save(config); access.browseSession().reset()
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            preferences.autoHide(true); preferences.idleSeconds(3)
            ActivityScenario.launch<FloatingSettingsActivity>(Intent(app, FloatingSettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.start_stop).performClick() }
                tapRemainingIcon()
                await("Picker opened through icon must load Immich") { val picker = resumedPicker() ?: return@await false
                    var ready = false; instrumentation.runOnMainSync { ready = model(picker).state.value.let { !it.loading && !it.error && it.items.isNotEmpty() } }; ready }
                instrumentation.runOnMainSync { resumedPicker()!!.findViewById<View>(R.id.search_toggle).performClick(); resumedPicker()!!.findViewById<EditText>(R.id.search).setText("😂") }
                await("Icon search must settle") { val picker = resumedPicker() ?: return@await false
                    var ready = false; instrumentation.runOnMainSync { ready = model(picker).state.value.let { it.query.text == "😂" && !it.loading && !it.error } }; ready }
                instrumentation.runOnMainSync { resumedPicker()!!.findViewById<View>(R.id.close).performClick() }
                tapRemainingIcon()
                instrumentation.runOnMainSync {
                    val picker = resumedPicker()!!
                    assertEquals("😂", model(picker).query.value.text)
                    assertEquals("😂", picker.findViewById<EditText>(R.id.search).text.toString())
                    picker.findViewById<View>(R.id.close).performClick()
                }
            }
            report.put("actual_half_hidden_icon_and_query_reopen", true)
            report.put("success", true)
        } finally {
            report.put("elapsed_seconds", (SystemClock.elapsedRealtime() - started) / 1000)
            File(app.filesDir, "live_immich_report.json").writeText(report.toString(2))
            input.delete()
            app.stopService(Intent(app, FloatingService::class.java))
            preferences.preferStickers(oldOptions.preferStickers); preferences.autoHide(oldOptions.autoHide); preferences.idleSeconds(oldOptions.idleSeconds)
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
