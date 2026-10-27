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

    @Test fun screensRemainInteractiveAfterForwardBackAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun destination(id: Int) {
                val deadline=SystemClock.elapsedRealtime()+5000
                var ready=false
                do {
                    scenario.onActivity { activity ->
                        val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
                        val fragment=host.childFragmentManager.primaryNavigationFragment
                        ready=host.navController.currentDestination?.id==id && fragment?.lifecycle?.currentState==Lifecycle.State.RESUMED
                        if(ready) {
                            val root=fragment!!.requireView() as ViewGroup
                            assertTrue(root.isShown); assertTrue(root.isTransitionGroup); settled(root)
                            if(id==R.id.homeFragment) assertEquals(activity.getString(R.string.app_name),activity.supportActionBar?.title?.toString())
                        }
                    }
                    if(!ready) SystemClock.sleep(30)
                } while(!ready && SystemClock.elapsedRealtime()<deadline)
                assertTrue("Navigation must settle at $id",ready)
            }
            fun navigate(id: Int) {
                val item=when(id) { R.id.settingsFragment -> R.id.settings; R.id.usageFragment -> R.id.usage; else -> error("Unsupported destination") }
                val deadline=SystemClock.elapsedRealtime()+5000
                var invoked=false
                do {
                    scenario.onActivity { activity ->
                        val menu=activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar).menu
                        // MenuHost may post its menu invalidation after the destination resumes.
                        if(menu.findItem(item)?.isVisible==true) invoked=menu.performIdentifierAction(item,0)
                    }
                    if(!invoked) SystemClock.sleep(30)
                } while(!invoked && SystemClock.elapsedRealtime()<deadline)
                assertTrue("The toolbar action must become available",invoked)
            }
            fun back() = scenario.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            destination(R.id.homeFragment)
            navigate(R.id.settingsFragment); destination(R.id.settingsFragment)
            scenario.recreate(); destination(R.id.settingsFragment)
            back(); destination(R.id.homeFragment)
            navigate(R.id.usageFragment); destination(R.id.usageFragment)
            back(); destination(R.id.homeFragment)
            navigate(R.id.settingsFragment); destination(R.id.settingsFragment)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.language_select).isClickable)
            }
        }
    }
}
