package one.njk.sao.floating

import android.content.ComponentName
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.core.app.LocaleManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import one.njk.sao.AppLanguages
import one.njk.sao.SettingsActivity
import one.njk.sao.R
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real framework resources and cross-process source UI, using only pseudolocales/synthetic data. */
class LanguageSupportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(40)
        assertTrue(label, condition())
    }
    private fun select(tag: String) {
        instrumentation.runOnMainSync { AppLanguages.select(app, tag.ifEmpty { null }) }
        await("Language applied: $tag") {
            LocaleManagerCompat.getApplicationLocales(app).toLanguageTags() == tag &&
                (tag.isEmpty() || AppLanguages.tags == tag)
        }
    }

    @Test fun scopedLocalesValidateInputAndDoNotMutateTheirBase() {
        val before = SourceLocales.languageTags(app)
        val rtl = SourceLocales.context(app, "ar-XB")
        assertEquals(Configuration.SCREENLAYOUT_LAYOUTDIR_RTL, rtl.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_LAYOUTDIR_MASK)
        assertEquals("ar-XB", SourceLocales.languageTags(rtl))
        assertEquals(before, SourceLocales.languageTags(app))
        assertEquals("zh-Hant-TW,en", SourceLocales.validated("zh-hant-tw,en"))
        assertNull(SourceLocales.validated("en--US"))
        assertNull(SourceLocales.validated("und"))
        assertNull(SourceLocales.validated("x".repeat(257)))
        assertNull(SourceLocales.validated(List(9) { "en" }.joinToString(",")))
        assertEquals(before, SourceLocales.languageTags(SourceLocales.context(app, "invalid___locale")))
        val english = SourceLocales.context(app, "en")
        val accented = SourceLocales.context(app, "en-XA")
        assertNotEquals(english.getString(R.string.language_system), accented.getString(R.string.language_system))
        assertTrue(english.resources.getQuantityString(R.plurals.import_added_count, 2, 2).contains("2"))
        assertEquals("All", sourceDisplayName(accented,"user-owned","All"))
        assertNotEquals("All", sourceDisplayName(accented,ALL_SOURCE_ID,"All"))
        val choices = AppLanguages.choices(app)
        assertTrue(choices.containsAll(listOf("en", "en-XA", "ar-XB")))
        assertEquals(choices.distinct(), choices)
        if (Build.VERSION.SDK_INT >= 33) {
            val supported = requireNotNull(android.app.LocaleConfig(app).supportedLocales)
            assertEquals(choices.toSet(), (0 until supported.size()).map { supported[it].toLanguageTag() }.toSet())
        }
    }

    @Test fun languageChipSwitchesAndRecreationPreservesSettingsNavigation() {
        val previous = LocaleManagerCompat.getApplicationLocales(app).toLanguageTags()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            try {
                select("")
                scenario.onActivity { it.findViewById<Chip>(R.id.language_select).performClick() }
                onView(withText(AppLanguages.label("en-XA"))).inRoot(isDialog()).perform(click())
                await("Requested locale persisted by Android/AppCompat") {
                    LocaleManagerCompat.getApplicationLocales(app).toLanguageTags() == "en-XA"
                }
                scenario.onActivity { activity ->
                    assertEquals("en-XA", activity.resources.configuration.locales[0].toLanguageTag())
                    assertEquals("en-XA", AppLanguages.tags)
                    val chip = activity.findViewById<Chip>(R.id.language_select)
                    assertTrue(chip.isActivated)
                    assertEquals(AppLanguages.label("en-XA"), chip.text.toString())
                    assertTrue(activity.getString(R.string.language_system).startsWith("["))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<Chip>(R.id.language_select))
                    assertEquals("en-XA", activity.resources.configuration.locales[0].toLanguageTag())
                }
                select("")
                await("Settings recreated with system default") {
                    var ready = false
                    scenario.onActivity { activity -> ready = activity.findViewById<Chip>(R.id.language_select)?.isActivated == false }
                    ready
                }
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.language_system), activity.findViewById<Chip>(R.id.language_select).text.toString())
                }
            } finally { select(previous) }
        }
    }

    @Test fun everySourceSettingsWindowUsesTheHostLanguage(): Unit = runBlocking {
        val previous = LocaleManagerCompat.getApplicationLocales(app).toLanguageTags()
        ActivityScenario.launch(FloatingSettingsActivity::class.java).use {
            try {
                select("en-XA")
                val installed = testExtensions(app).installed()
                val packages = listOf("immich", "local", "moeview", "findthatmeme", "googlephotos")
                for (name in packages) {
                    val extension = installed.single { it.component.packageName == "one.njk.sao.extension.$name" }
                    val strings = SourceLocales.context(app.createPackageContext(extension.component.packageName, 0), "en-XA")
                    val resource = strings.resources.getIdentifier(if (name == "immich") "immich_back" else "back", "string", extension.component.packageName)
                    assertNotEquals(0, resource)
                    ExtensionForm(installed = extension).use { form ->
                        // On API 32 the active accessibility root can remain the transparent
                        // host proxy during remote Activity recreation. Inspect its actual window.
                        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        }
                        val expected = strings.getString(resource)
                        await("$name source language") {
                            val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root } +
                                listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                            roots.filter { it.packageName?.toString() == extension.component.packageName }
                                .flatMap { it.findAccessibilityNodeInfosByViewId("${extension.component.packageName}:id/back") }
                                .any { it.text?.toString() == expected }
                        }
                    }
                }
            } finally {
                // Each authenticated source proxy owns its task, so the initial host window may
                // have been destroyed by ActivityScenario. Restore through a real active delegate.
                ActivityScenario.launch(FloatingSettingsActivity::class.java).use { select(previous) }
            }
        }
    }

    @Test fun concurrentRpcLocalesKeepProfilesQueriesAndCallerPayloadStable(): Unit = runBlocking {
        val component = ComponentName("one.njk.sao.extension.immich", "one.njk.sao.extension.immich.ImmichExtensionService")
        val english = ExtensionClient(app, component) { "en" }
        val accented = ExtensionClient(app, component) { "en-XA" }
        val id = UUID.randomUUID().toString()
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody("""{"smartSearch":true,"ocr":true}""")
            }
            val imported = SourceWire.profile(english.request(id, ExtensionProtocol.IMPORT, JSONObject().put("name", "User-owned नाम 📷")
                .put("configuration", JSONObject().put("url", server.url("/").toString()).put("key", "synthetic-key"))))
            try {
                val (a, b) = coroutineScope {
                    val one = async { english.profiles().single { it.id == id } }
                    val two = async { accented.profiles().single { it.id == id } }
                    one.await() to two.await()
                }
                assertEquals(a.id, b.id); assertEquals(a.name, b.name); assertEquals(a.revision, b.revision)
                assertNotEquals(a.summary, b.summary)
                val payload = JSONObject().put("revision", imported.revision).put("source", "language-test")
                val first = SourceWire.capabilities(english.request(id, ExtensionProtocol.CAPABILITIES, payload))
                val second = SourceWire.capabilities(accented.request(id, ExtensionProtocol.CAPABILITIES, payload))
                assertFalse(payload.has(SourceLocales.REQUEST_LANGUAGE_TAGS))
                assertEquals(first.categories.map { it.id to it.query }, second.categories.map { it.id to it.query })
                assertNotEquals(first.categories.map { it.label }, second.categories.map { it.label })
                assertEquals("cat", first.categories.single { it.id == "cats" }.query.text)
                assertEquals(a.summary, english.profiles().single { it.id == id }.summary)
                val previous = LocaleManagerCompat.getApplicationLocales(app).toLanguageTags()
                ActivityScenario.launch(FloatingSettingsActivity::class.java).use {
                    val extension = testExtensions(app).installed().single { it.component == component }
                    val managed = ExtensionMediaSource(app, extension.profile(imported), testExtensions(app), SourceMediaCache(app))
                    val values = java.util.concurrent.CopyOnWriteArrayList<SourceCapabilities>()
                    select("en")
                    val observer = launch(Dispatchers.Default) {
                        managed.capabilities.dropWhile { it.modes.isEmpty() }.collect { values += it }
                    }
                    try {
                        await("Initial source capabilities") { values.isNotEmpty() }
                        select("en-XA")
                        await("Localized capabilities") { values.lastOrNull()?.categories?.map { it.label } == second.categories.map { it.label } }
                        assertTrue("A label refresh must retain usable search modes", values.all { it.modes.isNotEmpty() })
                        assertTrue("A label refresh must retain category queries", values.all { caps ->
                            caps.categories.map { it.id to it.query } == first.categories.map { it.id to it.query }
                        })
                    } finally {
                        observer.cancelAndJoin(); managed.close(); select(previous)
                    }
                }
            } finally {
                english.request(id, ExtensionProtocol.DELETE_PROFILE, JSONObject().put("revision", imported.revision))
            }
        }
    }
}
