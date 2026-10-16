package one.njk.sao.floating

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import one.njk.sao.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Durable intent is separate from browsing; cross-process callback tests live in tools/. */
class FloatingRuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val preferences = FloatingPreferences(app)
    private val runtime get() = EntryPointAccessors.fromApplication(app, FloatingTestAccess::class.java).runtime()
    private var previousOverlay = AppOpsManager.MODE_DEFAULT
    private var previousLaunch = true
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes(); Unit }
    }
    private fun icon() = WindowInspector.getGlobalWindowViews().filterIsInstance<FloatingActionButton>().singleOrNull()
    private fun foregroundNotification() = app.getSystemService(NotificationManager::class.java).activeNotifications
        .any { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 }
    private fun await(label: String, ready: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 6_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < until) {
            instrumentation.runOnMainSync { done = ready() }; SystemClock.sleep(25)
        }
        assertTrue(label, done)
    }
    @Before fun setup() = runBlocking {
        previousLaunch = preferences.current().showOnLaunch
        previousOverlay = app.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.applicationInfo.uid, app.packageName)
        runtime.stop().join()
        preferences.showOnLaunch(false)
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        await("Fixture must stop the previous service") { !FloatingSession.active.value }
    }
    @After fun cleanup() = runBlocking {
        runtime.stop().join()
        await("Cleanup must remove the icon") { !FloatingSession.active.value && icon() == null }
        preferences.showOnLaunch(previousLaunch)
        shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW " + when (previousOverlay) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            else -> "default"
        })
    }

    @Test fun discardedServiceCanRestoreWithoutChangingBrowsingPreferences() = runBlocking {
        val preferencesFile = File(app.filesDir, "datastore/floating_picker.preferences_pb")
        val before = preferencesFile.readBytes()
        ActivityScenario.launch(MainActivity::class.java).use {
            runtime.show().join()
            await("Explicit Show must start the service") { FloatingSession.active.value && icon() != null }
            assertTrue("The running icon must have its actual foreground notification", foregroundNotification())
            assertTrue(runtime.isRequested())
            // OEMs may discard the started-service record, which START_STICKY cannot recover.
            app.stopService(Intent(app, FloatingService::class.java))
            await("The original service must really be destroyed") { !FloatingSession.active.value && icon() == null }
            assertTrue("Unexpected teardown must retain the user's run intent", runtime.isRequested())
            runtime.restore().join()
            await("Recovery must recreate the icon") { FloatingSession.active.value && icon() != null }
            assertTrue("Recovery must restore the notification as well", foregroundNotification())
            assertArrayEquals(before, preferencesFile.readBytes())
            assertTrue("Run intent must be excluded from backup", File(app.noBackupFilesDir, "floating_runtime.preferences_pb").isFile)
        }
    }

    @Test fun explicitStopIsDurableAndRepeatedRecoveryDoesNotUndoIt() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            runtime.show().join()
            await("Explicit Show must start the icon") { FloatingSession.active.value && icon() != null }
            instrumentation.runOnMainSync { assertTrue(icon()!!.performLongClick()) }
            await("Manual dismissal must stop the icon") { !FloatingSession.active.value && icon() == null }
            assertFalse("Dismissal must remove the foreground notification", foregroundNotification())
            assertFalse("Dismissal must be committed before destruction", runtime.isRequested())
            val file = File(app.noBackupFilesDir, "floating_runtime.preferences_pb")
            val before = file.readBytes()
            repeat(3) { runtime.restore().join() }
            SystemClock.sleep(250)
            instrumentation.runOnMainSync { assertNull(icon()); assertFalse(FloatingSession.active.value) }
            assertArrayEquals("Recovery must only read run intent", before, file.readBytes())
        }
    }

    @Test fun recoveryWhileRunningReusesTheExistingOverlay() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            runtime.show().join()
            await("Explicit Show must start the icon") { FloatingSession.active.value && icon() != null }
            var original: FloatingActionButton? = null
            instrumentation.runOnMainSync { original = icon() }
            repeat(3) { runtime.restore().join() }
            instrumentation.runOnMainSync { assertSame(original, icon()) }
            runtime.stop().join()
            assertFalse(runtime.isRequested())
        }
    }
}
