package one.njk.sao.floating

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import one.njk.sao.R
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** The real oversized-media UI, with synthetic source bytes and intercepted sends. */
class AttachmentLimitDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val access get()=EntryPointAccessors.fromApplication(app,FloatingTestAccess::class.java)

    @Test fun discordAsksOnlyOnDeliveryAndSupportsCancelOriginalAndSmallerCopy(): Unit=runBlocking {
        val profiles=access.sources(); val previous=profiles.snapshots.first()
        val preferences=access.preferences(); val before=preferences.current()
        val image=instrumentation.context.assets.open("floating/happy.png").use { it.readBytes() }
        val bitmap=android.graphics.BitmapFactory.decodeByteArray(image,0,image.size)
        val jpeg=java.io.ByteArrayOutputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,100,output); bitmap.recycle(); output.toByteArray() }
        // A valid JPEG with harmless trailing data tests the real 20 MiB boundary cheaply.
        val original=jpeg.copyOf((AttachmentSizePolicy.DISCORD_FREE_BYTES+1).toInt())
        val sent=AtomicReference<Intent?>()
        val large=AtomicBoolean(true)
        val server=MockWebServer().apply { dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse=when {
                request.path=="/api/server/features" -> MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
                request.path?.startsWith("/api/search/")==true -> MockResponse().setBody("""{"assets":{"items":[{"id":"00000000-0000-0000-0000-000000000099","type":"IMAGE","originalFileName":"large-test.jpg","originalMimeType":"image/jpeg","updatedAt":"2026-10-21T00:00:0${if(large.get()) 0 else 1}Z"}]}}""")
                request.path?.contains("/original")==true -> MockResponse().setBody(Buffer().write(if(large.get()) original else jpeg))
                request.path?.contains("/thumbnail")==true -> MockResponse().setBody(Buffer().write(jpeg))
                else -> MockResponse().setBody("[]")
            }
        }; start() }
        val monitor=object: Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_SEND) return null
                sent.set(intent); return Instrumentation.ActivityResult(Activity.RESULT_OK,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            profiles.restore(SourceCatalog())
            profiles.upsert(immichProfile("Discord size fixture",ImmichConfig(server.url("/").toString(),"size-fixture-key")),true)
            preferences.preferStickers(false); access.browseSession().reset()
            suspend fun screen(reduce: Boolean) {
                sent.set(null)
                ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,"com.discord",null)).use { scenario ->
                    await(scenario) { ViewModelProvider(it)[PickerViewModel::class.java].state.value.items.isNotEmpty() && it.findViewById<View>(R.id.media_action).isEnabled }
                    if(!reduce) {
                        scenario.onActivity { it.findViewById<View>(R.id.copy_action).performClick() }
                        await(scenario) { it.getSystemService(ClipboardManager::class.java).primaryClipDescription?.label?.toString()=="large-test.jpg" }
                        onView(withText(R.string.media_discord_size_title)).check(doesNotExist())
                    }
                    scenario.onActivity { it.findViewById<View>(R.id.media_action).performClick() }
                    awaitDialog()
                    if(!reduce) {
                        pauseSourceVisual("discord-large-attachment")
                        onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
                        await(scenario) { it.findViewById<View>(R.id.media_action).isEnabled }
                        assertNull(sent.get())
                        scenario.onActivity { it.findViewById<View>(R.id.media_action).performClick() }
                        awaitDialog()
                    }
                    onView(withText(if(reduce) R.string.media_smaller_copy else R.string.media_use_original)).inRoot(isDialog()).perform(click())
                    withTimeout(10_000) { while(sent.get()==null) delay(30) }
                    val send=sent.get()!!
                    assertEquals("com.discord",send.`package`); assertEquals("image/jpeg",send.type)
                    val uri=send.getParcelableExtra(Intent.EXTRA_STREAM,android.net.Uri::class.java)!!
                    val bytes=app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    if(reduce) {
                        assertTrue(bytes.size<=AttachmentSizePolicy.DISCORD_FREE_BYTES)
                        assertTrue(uri.toString().contains("-limited-"))
                    } else assertArrayEquals(original,bytes)
                }
            }
            screen(false); screen(true)
            large.set(false); sent.set(null); access.browseSession().reset()
            ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app,"com.discord",null)).use { scenario ->
                await(scenario) { ViewModelProvider(it)[PickerViewModel::class.java].state.value.items.isNotEmpty() && it.findViewById<View>(R.id.media_action).isEnabled }
                scenario.onActivity { it.findViewById<View>(R.id.media_action).performClick() }
                withTimeout(10_000) { while(sent.get()==null) delay(30) }
                // Reaching the intercepted send without answering anything proves no prompt.
                // PickerActivity has finished by this point, so Espresso has no resumed root.
                val uri=sent.get()!!.getParcelableExtra(Intent.EXTRA_STREAM,android.net.Uri::class.java)!!
                assertArrayEquals(jpeg,app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        } finally {
            instrumentation.removeMonitor(monitor); profiles.restore(previous)
            preferences.preferStickers(before.preferStickers); access.browseSession().reset(); server.shutdown()
        }
    }

    private fun awaitDialog() {
        val until=SystemClock.elapsedRealtime()+15_000
        while(SystemClock.elapsedRealtime()<until) {
            try { onView(withText(R.string.media_discord_size_title)).inRoot(isDialog()).check(matches(isDisplayed())); return }
            catch(_: androidx.test.espresso.NoMatchingViewException) { SystemClock.sleep(40) }
        }
        onView(withText(R.string.media_discord_size_title)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun await(scenario: ActivityScenario<PickerActivity>, condition: (PickerActivity)->Boolean) {
        val until=SystemClock.elapsedRealtime()+15_000
        var ready=false
        while(!ready && SystemClock.elapsedRealtime()<until) {
            scenario.onActivity { ready=condition(it) }; if(!ready) SystemClock.sleep(40)
        }
        assertTrue("Picker or attachment choice must be ready",ready)
    }
}
