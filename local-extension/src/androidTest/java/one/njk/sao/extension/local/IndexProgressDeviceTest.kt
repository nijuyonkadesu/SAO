package one.njk.sao.extension.local

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import one.njk.sao.extension.local.databinding.IndexProgressBinding
import org.junit.Assert.*
import org.junit.Test

class IndexProgressDeviceTest {
    @Test fun progressShowsSeparateCountsAndStopsAnimatingWhenPausedOrFinished() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=ContextThemeWrapper(instrumentation.targetContext,R.style.Theme_LocalExtension)
            val binding=IndexProgressBinding.inflate(LayoutInflater.from(context))
            val scanning=IndexStatus(12,8,1,true,0,images=10,animated=3,videos=2)
            binding.bind(scanning,false,IndexWork.RUNNING)
            assertTrue(binding.progress.isIndeterminate)
            assertEquals("Found so far: 10 images (3 animated) · 2 videos",binding.mediaCounts.text.toString())
            assertEquals("Labels/OCR: 3 processed · 8 remaining · 1 failed",binding.processingCounts.text.toString())
            binding.bind(scanning,true,IndexWork.RUNNING)
            assertFalse(binding.progress.isIndeterminate); assertEquals(View.GONE,binding.progress.visibility)
            assertEquals(context.getString(R.string.album_paused),binding.phase.text)
            binding.bind(scanning.copy(scanning=false,pending=0,scanned=true),false,IndexWork.IDLE)
            assertEquals(context.getString(R.string.album_errors),binding.phase.text)
            assertEquals(1000,binding.progress.progress)
            binding.bind(scanning.copy(scanning=false,pending=0,failed=0,scanned=true),false,IndexWork.IDLE)
            assertEquals(context.getString(R.string.album_complete),binding.phase.text)
            binding.bind(scanning.copy(scanning=false,contextSearch=true,contextProcessed=1),false,IndexWork.RUNNING)
            assertEquals(208,binding.progress.progress)
            assertEquals(context.getString(R.string.album_indexing),binding.phase.text)
            binding.bind(null,false,IndexWork.IDLE)
            assertEquals(View.GONE,binding.mediaCounts.visibility)
        }
    }
}
