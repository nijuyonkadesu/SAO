package one.njk.sao.floating.nativebridge

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.floating.FloatingSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A stalled recording receiver must not stall picker access or queue another send. */
class NativeBridgeOperationDeviceTest {
    @Test fun replacedClientCompletionCannotClearTheNewClientsOperation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val before = FloatingSession.active.value
        val releaseA = CountDownLatch(1); val releaseB = CountDownLatch(1)
        val enteredA = CompletableDeferred<Unit>(); val enteredB = CompletableDeferred<Unit>()
        val file = File(app.filesDir,"floating_exports/v3/bridge-replacement-fixture.gif").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
        }
        fun client(token: String,entered: CompletableDeferred<Unit>,release: CountDownLatch) = object : TestNativeClient() {
            override fun setActive(active: Boolean,generation: Long) = Unit
            override fun snapshot(beginSelection: Boolean) = Bundle().apply {
                putString("session",token); putLong("created",SystemClock.elapsedRealtime())
                putBoolean("resumed",true); putStringArray("mime_types",arrayOf("image/gif"))
            }
            override fun insert(uri: Uri,mimeType: String,session: String,requestId: Long): Int {
                entered.complete(Unit); check(release.await(10,TimeUnit.SECONDS))
                return NativeInsertResult.ACCEPTED.wireValue
            }
        }
        NativeBridgeRegistry.clear(); FloatingSession.active(true)
        try {
            val uri = FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",file)
            NativeBridgeRegistry.register(app.packageName,client("original",enteredA,releaseA))
            NativeBridgeRegistry.beginSelection(app.packageName)
            val first = async(Dispatchers.IO) { NativeBridgeRegistry.insert(app,app.packageName,uri,"image/gif","original") }
            try {
                withTimeout(5_000) { enteredA.await() }
                NativeBridgeRegistry.register(app.packageName,client("replacement",enteredB,releaseB))
                assertEquals("replacement",NativeBridgeRegistry.beginSelection(app.packageName)!!.session)
                val second = async(Dispatchers.IO) { NativeBridgeRegistry.insert(app,app.packageName,uri,"image/gif","replacement") }
                try {
                    withTimeout(5_000) { enteredB.await() }
                    releaseA.countDown(); assertEquals(NativeInsertResult.ACCEPTED,first.await())
                    assertTrue(app.packageName in NativeBridgeRegistry.inserting.value)
                    assertNull(withTimeout(500) { NativeBridgeRegistry.beginSelection(app.packageName) })
                    assertEquals("replacement",NativeBridgeRegistry.sessions.value[app.packageName]?.session)
                } finally { releaseB.countDown() }
                assertEquals(NativeInsertResult.ACCEPTED,second.await())
            } finally { releaseA.countDown(); releaseB.countDown() }
        } finally {
            NativeBridgeRegistry.clear(); FloatingSession.active(before); file.delete()
        }
    }
    @Test fun slowInsertDoesNotBlockSelectionAndIsNotReplayed() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val before = FloatingSession.active.value
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val sends = AtomicInteger()
        val file = File(app.filesDir,"floating_exports/v3/bridge-operation-fixture.gif").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
        }
        fun client(block: Boolean) = object : TestNativeClient() {
            override fun setActive(active: Boolean,generation: Long) = Unit
            override fun snapshot(beginSelection: Boolean) = Bundle().apply {
                putString("session","synthetic-operation"); putLong("created",SystemClock.elapsedRealtime())
                putBoolean("resumed",true); putStringArray("mime_types",arrayOf("image/gif"))
            }
            override fun insert(uri: Uri,mimeType: String,session: String,requestId: Long): Int {
                sends.incrementAndGet(); started.complete(Unit)
                if(block) check(release.await(10,TimeUnit.SECONDS))
                return NativeInsertResult.ACCEPTED.wireValue
            }
        }
        NativeBridgeRegistry.clear(); FloatingSession.active(true)
        try {
            NativeBridgeRegistry.register(app.packageName,client(true))
            NativeBridgeRegistry.register("synthetic.other",client(false))
            val selected = NativeBridgeRegistry.beginSelection(app.packageName)!!
            val uri = FileProvider.getUriForFile(app,"${app.packageName}.fileprovider",file)
            val original = async(Dispatchers.IO) { NativeBridgeRegistry.insert(app,app.packageName,uri,"image/gif",selected.session) }
            try {
                withTimeout(5_000) { started.await() }
                assertTrue(app.packageName in NativeBridgeRegistry.inserting.value)
                assertFalse(NativeBridgeRegistry.supports(app.packageName,selected.session,"image/gif"))
                assertNull(withTimeout(500) { NativeBridgeRegistry.beginSelection(app.packageName) })
                assertNotNull(withTimeout(500) { NativeBridgeRegistry.beginSelection("synthetic.other") })
                assertEquals(NativeInsertResult.REJECTED,withTimeout(500) {
                    NativeBridgeRegistry.insert(app,app.packageName,uri,"image/gif",selected.session)
                })
                assertEquals(1,sends.get())
            } finally { release.countDown() }
            assertEquals(NativeInsertResult.ACCEPTED,withTimeout(5_000) { original.await() })
            assertTrue(NativeBridgeRegistry.inserting.value.isEmpty())
            assertNotNull(NativeBridgeRegistry.beginSelection(app.packageName))
        } finally {
            release.countDown(); NativeBridgeRegistry.clear(); FloatingSession.active(before); file.delete()
        }
    }
}
