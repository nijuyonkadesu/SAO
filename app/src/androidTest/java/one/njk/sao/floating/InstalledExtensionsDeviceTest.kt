package one.njk.sao.floating

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.njk.sao.R
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class InstalledExtensionsDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    @Test fun packageInventoryIncludesExtensionsWithoutProfilesAndDisabledApps(): Unit=runBlocking {
        val extensions=testExtensions(app)
        val provider=extensions.installed().first { it.component.packageName=="one.njk.sao.extension.moeview" }
        assertTrue(extensions.installedApps().any { it.packageName==provider.component.packageName })
        val original=app.packageManager.getApplicationEnabledSetting(provider.component.packageName)
        try {
            shell("pm disable-user --user 0 ${provider.component.packageName}")
            assertFalse(extensions.installed().any { it.component==provider.component })
            assertTrue(extensions.installedApps().any { it.packageName==provider.component.packageName })
        } finally {
            val operation=when(original) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disable-user"
                else -> "default-state"
            }
            shell("pm $operation --user 0 ${provider.component.packageName}")
        }
    }
    @Test fun extensionRowsLaunchSystemControlsForTheChosenPackage() {
        val captured=AtomicReference<Intent?>()
        val monitor=object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action !in setOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Intent.ACTION_DELETE)) return null
                captured.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<SourceSettingsActivity>(Intent(app,SourceSettingsActivity::class.java)).use { scenario ->
                val target="one.njk.sao.extension.moeview"
                var row: View?=null
                await(scenario,"Extension row appears") { activity ->
                    val state=ViewModelProvider(activity)[SourceProfilesViewModel::class.java].state.value
                    val list=activity.findViewById<RecyclerView>(R.id.source_list)
                    val adapters=(list.adapter as ConcatAdapter).adapters
                    val inventory=adapters.filterIsInstance<InstalledExtensionsAdapter>().single()
                    val profileCount=adapters.takeWhile { it!==inventory }.sumOf { it.itemCount }
                    val index=inventory.currentList.indexOfFirst { it.app?.packageName==target }
                    if(!state.loaded || index<0) false else {
                        list.scrollToPosition(profileCount+index)
                        row=list.findViewHolderForAdapterPosition(profileCount+index)?.itemView
                        row!=null
                    }
                }
                pauseSourceVisual("installed-extensions")
                scenario.onActivity { row!!.findViewById<View>(R.id.app_info).performClick() }
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,captured.get()?.action)
                assertEquals("package:$target",captured.get()?.dataString)
                captured.set(null)
                scenario.onActivity { row!!.findViewById<View>(R.id.uninstall).performClick() }
                assertEquals(Intent.ACTION_DELETE,captured.get()?.action)
                assertEquals("package:$target",captured.get()?.dataString)
                assertNotNull(app.packageManager.getPackageInfo(target,0))
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun await(scenario: ActivityScenario<SourceSettingsActivity>,label: String,condition: (SourceSettingsActivity)->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+15_000; var ready=false
        while(!ready && SystemClock.elapsedRealtime()<deadline) { scenario.onActivity { ready=condition(it) }; SystemClock.sleep(50) }
        assertTrue(label,ready)
    }
}
