package one.njk.sao.floating

import android.content.ClipboardManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inspector.WindowInspector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import one.njk.sao.floating.emoji.EmojiSearchView
import one.njk.sao.floating.emoji.EmojiSuggestion
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class EmojiAutocompleteDeviceTest {
    @get:Rule val marquees = PickerMarqueeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java)
    private lateinit var previous: SourceCatalog
    @Before fun setup() = runBlocking {
        previous = access.sources().current(); access.sources().restore(SourceCatalog()); access.browseSession().reset()
    }
    @After fun cleanup() = runBlocking {
        access.sources().restore(previous); access.browseSession().reset(); instrumentation.setInTouchMode(true)
    }

    private fun editor(activity: PickerActivity) = activity.findViewById<EmojiSearchView>(R.id.search)
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
        val ready = predicate()
        if (!ready) shot("failure")
        assertTrue(label, ready)
    }
    private fun await(scenario: ActivityScenario<PickerActivity>, label: String, predicate: (PickerActivity) -> Boolean) = await(label) {
        var ready = false; scenario.onMainActivity { ready = predicate(it) }; ready
    }
    private fun launch(action: (ActivityScenario<PickerActivity>) -> Unit) {
        ActivityScenario.launch<PickerActivity>(PickerActivity.intent(app, null, null)).use { scenario ->
            if (InstrumentationRegistry.getArguments().getString("emojiOrientation") == "landscape") {
                // A translucent picker otherwise inherits the launcher's fixed portrait orientation.
                scenario.onMainActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await(scenario, "Picker must actually be in landscape") {
                    it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                        it.window.decorView.width > it.window.decorView.height && it.window.decorView.hasWindowFocus()
                }
            }
            await(scenario, "Samples must load") { !ViewModelProvider(it)[PickerViewModel::class.java].state.value.loading }
            scenario.onMainActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
            await(scenario, "Search and its keyboard must open") { editor(it).hasFocus() && ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            action(scenario)
        }
    }
    private fun type(scenario: ActivityScenario<PickerActivity>, value: String, cursor: Int = value.length) {
        scenario.onMainActivity { editor(it).apply { requestFocus(); setText(value); setSelection(cursor) } }
    }
    private fun first(activity: PickerActivity) = editor(activity).adapter.getItem(0) as EmojiSuggestion
    private fun shot(suffix: String) {
        val label = InstrumentationRegistry.getArguments().getString("emojiShots") ?: return
        SystemClock.sleep(200)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(app.filesDir, "emoji-$label-$suffix.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
    }

    @Test fun aRealSuggestionTapReplacesOnlyTheShortcutAndKeepsKeyboardAndClipboard() = launch { scenario ->
        val text = "happy :wink later"
        var clipboard: String? = null
        scenario.onMainActivity { clipboard = app.getSystemService(ClipboardManager::class.java).primaryClip?.toString() }
        type(scenario, text, text.indexOf("wink") + 4)
        await(scenario, "Exact wink shortcut must be first in the popup") { editor(it).isPopupShowing && first(it).emoji == "😉" }
        shot("suggestions")
        val bounds = Rect()
        instrumentation.runOnMainSync {
            val row = WindowInspector.getGlobalWindowViews().asSequence().flatMap { it.descendants() }
                .first { it.id == R.id.emoji_suggestion && it.isShown }
            assertTrue(row.getGlobalVisibleRect(bounds))
            val location = IntArray(2).also(row::getLocationOnScreen)
            bounds.set(location[0], location[1], location[0] + row.width, location[1] + row.height)
        }
        val down = SystemClock.uptimeMillis()
        for (event in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), event, bounds.exactCenterX(), bounds.exactCenterY(), 0).let {
                instrumentation.uiAutomation.injectInputEvent(it, true); it.recycle()
            }
        }
        await(scenario, "Tapping must replace the shortcut, preserving surrounding query text") { editor(it).text.toString() == "happy 😉 later" }
        scenario.onMainActivity {
            assertEquals("happy 😉".length, editor(it).selectionStart)
            assertTrue(editor(it).hasFocus()); assertFalse(editor(it).isPopupShowing)
            assertTrue(ViewCompat.getRootWindowInsets(it.window.decorView)!!.isVisible(WindowInsetsCompat.Type.ime()))
            assertEquals(clipboard, app.getSystemService(ClipboardManager::class.java).primaryClip?.toString())
            assertEquals("happy 😉 later", ViewModelProvider(it)[PickerViewModel::class.java].query.value.text)
        }
        shot("inserted")
    }

    @Test fun newerTextCursorMovementBackAndReopeningCannotReshowObsoleteSuggestions() = launch { scenario ->
        type(scenario, ":wink"); type(scenario, ":heart")
        await(scenario, "A newer prefix must replace obsolete wink matches") { editor(it).isPopupShowing && first(it).emoji.replace("\ufe0f", "") == "❤" }
        scenario.onMainActivity { editor(it).setSelection(0) }
        await(scenario, "Moving outside a shortcut must hide suggestions") { !editor(it).isPopupShowing }
        scenario.onMainActivity { editor(it).setSelection(editor(it).length()) }
        await(scenario, "Returning the cursor to the shortcut must show matches") { editor(it).isPopupShowing }
        scenario.onMainActivity { it.onBackPressedDispatcher.onBackPressed() }
        await(scenario, "Back must dismiss suggestions without closing search") { !editor(it).isPopupShowing && editor(it).isShown }
        scenario.onMainActivity { editor(it).refreshSuggestions() }
        SystemClock.sleep(150)
        scenario.onMainActivity { assertFalse(editor(it).isPopupShowing); it.onBackPressedDispatcher.onBackPressed() }
        await(scenario, "Another Back must collapse search") { !editor(it).isShown }
        scenario.onMainActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
        await(scenario, "Reopening must preserve and select the whole query") {
            editor(it).text.toString() == ":heart" && editor(it).selectionStart == 0 && editor(it).selectionEnd == 6 && !editor(it).isPopupShowing
        }
        type(scenario, "keep this query")
        scenario.onMainActivity {
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
                editor(it).dispatchKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_ENTER))
        }
        await(scenario, "Hardware Enter must search without invoking the Clear end icon") {
            !editor(it).isShown && ViewModelProvider(it)[PickerViewModel::class.java].query.value.text == "keep this query"
        }
        scenario.onMainActivity { it.findViewById<View>(R.id.search_toggle).performClick() }
        type(scenario, ":zzzz_unmatched")
        await(scenario, "An unmatched shortcut must have no suggestion popup") { editor(it).adapter.count == 0 && !editor(it).isPopupShowing }
        scenario.onMainActivity { it.onBackPressedDispatcher.onBackPressed() }
        await(scenario, "An invisible lookup must not consume Back") { !editor(it).isShown }
    }

    @Test fun composingTextAndMaterialAccessibilityCompletionPreserveSurroundingText() = launch { scenario ->
        val text = "look :thumbsup after"
        type(scenario, text, text.indexOf(" after"))
        await(scenario, "Thumbs-up aliases must be available") { editor(it).isPopupShowing && first(it).emoji == "👍" }
        scenario.onMainActivity {
            val view = editor(it)
            view.onCreateInputConnection(EditorInfo())!!.setComposingRegion(text.indexOf(':') + 1, view.selectionStart)
            // Material's accessibility popup calls this overload, while ordinary taps use replaceText.
            view.setText("👍", false)
            assertEquals("look 👍 after", view.text.toString())
            assertEquals("look 👍".length, view.selectionStart)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(view.text))
            assertTrue(view.hasFocus())
        }
        // setText does not enter keyboard mode as real hardware typing would.
        instrumentation.setInTouchMode(false)
        type(scenario, "code :woman_technologist after", "code :woman_technologist".length)
        await(scenario, "Joined Unicode emoji must be offered intact") { editor(it).isPopupShowing && first(it).emoji == "👩‍💻" }
        await("The native popup rows must be laid out before keyboard navigation") {
            var ready = false
            instrumentation.runOnMainSync {
                ready = WindowInspector.getGlobalWindowViews().asSequence().flatMap { it.descendants() }
                    .any { it.id == R.id.emoji_suggestion && it.isShown && it.width > 0 && it.height > 0 }
            }
            ready
        }
        // Real input must switch ListView out of touch mode before it exposes a keyboard selection.
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        await(scenario, "D-pad must highlight a native suggestion") { editor(it).listSelection >= 0 }
        scenario.onMainActivity { assertTrue(editor(it).completeHighlightedSuggestion()) }
        await(scenario, "Keyboard completion must preserve the whole emoji sequence") { editor(it).text.toString() == "code 👩‍💻 after" }
    }

    @Test fun largeTextSuggestionsRemainVisibleAboveTheKeyboard() {
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes().toString(Charsets.UTF_8).trim() }
        val oldScale = shell("settings get system font_scale")
        try {
            shell("settings put system font_scale 2.0")
            launch { scenario ->
                type(scenario, ":wink")
                await(scenario, "Large-text suggestions must open") { editor(it).isPopupShowing && first(it).emoji == "😉" }
                instrumentation.runOnMainSync {
                    val row = WindowInspector.getGlobalWindowViews().asSequence().flatMap { it.descendants() }
                        .first { it.id == R.id.emoji_suggestion && it.isShown }
                    val bounds = Rect(); assertTrue(row.getGlobalVisibleRect(bounds))
                    assertTrue("The row must have its full measured height", bounds.height() >= row.height - 1)
                }
                shot("large-text")
            }
        } finally {
            if (oldScale == "null") shell("settings delete system font_scale") else shell("settings put system font_scale $oldScale")
        }
    }
}
