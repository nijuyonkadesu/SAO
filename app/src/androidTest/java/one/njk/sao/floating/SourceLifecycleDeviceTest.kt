package one.njk.sao.floating

import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.ExtensionClient
import org.junit.Assert.*
import org.junit.Test

class SourceLifecycleDeviceTest {
    @Test fun serviceCancellationRepliesImmediatelyAndReadRebindsOnlyOnce() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        SourceLifecycleFixtureService.calls.set(0)
        val client=ExtensionClient(app,ComponentName(app,SourceLifecycleFixtureService::class.java))
        val result=withTimeout(5000) { ReadRetry.run { client.request(operation="read") } }
        assertTrue(result.getBoolean("ok")); assertEquals(2,SourceLifecycleFixtureService.calls.get())
        delay(1100); assertEquals(2,SourceLifecycleFixtureService.calls.get())
    }
}
