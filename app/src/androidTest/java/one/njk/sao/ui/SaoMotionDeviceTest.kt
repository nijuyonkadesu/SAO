package one.njk.sao.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import one.njk.sao.MainActivity
import one.njk.sao.R
import org.junit.Assert.*
import org.junit.Test

class SaoMotionDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()

    private class Owner: LifecycleOwner {
        override val lifecycle=LifecycleRegistry(this)
    }
    private fun settled(view: View) {
        assertEquals(1f,view.alpha,0.001f)
        assertEquals(0f,view.translationX,0.001f)
        assertEquals(0f,view.translationY,0.001f)
        assertEquals(1f,view.scaleX,0.001f); assertEquals(1f,view.scaleY,0.001f)
    }

    @Test fun hiddenAndStoppedPanelsNeverKeepAnIntermediateFrame() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                InstrumentationRegistry.getArguments().getString("animationsEnabled")?.let {
                    assertEquals("The device must exercise the requested animation setting",it.toBooleanStrict(),ValueAnimator.areAnimatorsEnabled())
                }
                val container=activity.findViewById<ViewGroup>(android.R.id.content)
                val panel=View(activity).apply { isVisible=false }
                container.addView(panel,ViewGroup.LayoutParams(200,100))
                val owner=Owner().apply { lifecycle.currentState=Lifecycle.State.RESUMED }
                val motion=SaoMotion(owner)
                motion.visible(panel,true)
                assertTrue("Visibility changes immediately; input never waits for a fade",panel.isVisible)
                if(!ValueAnimator.areAnimatorsEnabled()) settled(panel)
                motion.visible(panel,false)
                assertFalse(panel.isVisible); settled(panel)
                motion.visible(panel,true)
                owner.lifecycle.currentState=Lifecycle.State.CREATED
                assertTrue(panel.isVisible); settled(panel)
                motion.visible(panel,false); motion.visible(panel,true)
                settled(panel) // A stopped screen never starts a new effect.
                owner.lifecycle.currentState=Lifecycle.State.RESUMED
                motion.visible(panel,false); motion.visible(panel,true)
                owner.lifecycle.currentState=Lifecycle.State.DESTROYED
                settled(panel)
                container.removeView(panel)
            }
        }
    }

    @Test fun toolbarScreensUseOrdinaryActivitiesAndKeepHomeMounted() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            var homeView: View?=null
            scenario.onActivity { activity ->
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                homeView=host.childFragmentManager.primaryNavigationFragment!!.requireView()
                val menu=activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar).menu
                val ids=(0 until menu.size()).map { menu.getItem(it).itemId }
                assertTrue(ids.indexOf(R.id.edit)<ids.indexOf(R.id.usage))
                assertTrue(ids.indexOf(R.id.usage)<ids.indexOf(R.id.settings))
            }
            for((item,target) in listOf(
                R.id.settings to one.njk.sao.SettingsActivity::class.java,
                R.id.usage to one.njk.sao.usage.UsageActivity::class.java,
                R.id.edit to one.njk.sao.floating.SourceSettingsActivity::class.java
            )) {
                val monitor=instrumentation.addMonitor(target.name,null,false)
                try {
                    scenario.onActivity { activity ->
                        assertTrue(activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar).menu.performIdentifierAction(item,0))
                    }
                    val screen=instrumentation.waitForMonitorWithTimeout(monitor,5000) as? androidx.appcompat.app.AppCompatActivity
                    assertNotNull("The toolbar must open ${target.simpleName}",screen)
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertEquals(target,screen!!::class.java)
                        if(item!=R.id.edit) {
                            val fragment=screen.supportFragmentManager.findFragmentById(R.id.screen_content)!!
                            assertNull(fragment.enterTransition); assertNull(fragment.exitTransition)
                            assertTrue(screen.supportActionBar?.title?.isNotBlank()==true)
                        }
                        screen.onBackPressedDispatcher.onBackPressed()
                    }
                    val deadline=SystemClock.elapsedRealtime()+5000
                    var restored=false
                    do {
                        scenario.onActivity { activity ->
                            restored=activity.hasWindowFocus() && screen!!.isFinishing
                            val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                            assertEquals(R.id.homeFragment,host.navController.currentDestination!!.id)
                            assertSame("Activity navigation must keep the home View mounted",homeView,host.childFragmentManager.primaryNavigationFragment!!.requireView())
                        }
                        if(!restored) SystemClock.sleep(30)
                    } while(!restored && SystemClock.elapsedRealtime()<deadline)
                    assertTrue("Back must return to the same home Activity",restored)
                } finally { instrumentation.removeMonitor(monitor) }
            }
        }
        ActivityScenario.launch(one.njk.sao.SettingsActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity -> assertTrue(activity.findViewById<View>(R.id.language_select).isClickable) }
        }
    }
}
