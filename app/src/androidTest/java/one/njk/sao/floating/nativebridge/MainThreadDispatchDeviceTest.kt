package one.njk.sao.floating.nativebridge

import android.os.Handler
import android.os.HandlerThread
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainThreadDispatchDeviceTest {
    @Test fun queuedTimeoutPreventsLateDispatchAndDisposesExactlyOnce() {
        val thread=HandlerThread("dispatch-queued-test").apply { start() }
        val handler=Handler(thread.looper)
        val blocked=CountDownLatch(1); val release=CountDownLatch(1); val drained=CountDownLatch(1)
        val calls=AtomicInteger(); val cleanups=AtomicInteger()
        try {
            handler.post { blocked.countDown(); release.await(2,TimeUnit.SECONDS) }
            assertTrue(blocked.await(2,TimeUnit.SECONDS))
            assertEquals(NativeInsertResult.REJECTED,MainThreadDispatch.run(handler,{ calls.incrementAndGet(); true },30) { cleanups.incrementAndGet() })
            release.countDown(); handler.post { drained.countDown() }
            assertTrue(drained.await(2,TimeUnit.SECONDS))
            assertEquals(0,calls.get()); assertEquals(1,cleanups.get())
        } finally { release.countDown(); thread.quitSafely(); thread.join(2000) }
    }

    @Test fun runningTimeoutIsUnknownAndRetainsPreparedContentUntilDispatchActuallyEnds() {
        val thread=HandlerThread("dispatch-running-test").apply { start() }
        val handler=Handler(thread.looper)
        val started=CountDownLatch(1); val release=CountDownLatch(1); val disposed=CountDownLatch(1)
        val calls=AtomicInteger(); val cleanups=AtomicInteger()
        try {
            val result=MainThreadDispatch.run(handler,{
                started.countDown(); release.await(2,TimeUnit.SECONDS); calls.incrementAndGet(); true
            },100) { cleanups.incrementAndGet(); disposed.countDown() }
            assertTrue(started.await(1,TimeUnit.SECONDS))
            assertEquals(NativeInsertResult.UNKNOWN,result)
            assertEquals(0,cleanups.get())
            release.countDown(); assertTrue(disposed.await(2,TimeUnit.SECONDS))
            assertEquals(1,calls.get()); assertEquals(1,cleanups.get())
        } finally { release.countDown(); thread.quitSafely(); thread.join(2000) }
    }

    @Test fun completedDispatchReportsTheActualOutcome() {
        val thread=HandlerThread("dispatch-result-test").apply { start() }
        val handler=Handler(thread.looper)
        try {
            assertEquals(NativeInsertResult.ACCEPTED,MainThreadDispatch.run(handler,{ true },2000))
            assertEquals(NativeInsertResult.REJECTED,MainThreadDispatch.run(handler,{ false },2000))
        } finally { thread.quitSafely(); thread.join(2000) }
    }
}
