package one.njk.sao.floating

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

class ReadRetryTest {
    @Test fun networkReadRetriesOnceAndPersistentFailureStops(): Unit = runBlocking {
        var attempts = 0
        val result = ReadRetry.run { attempts++; if (attempts == 1) throw IOException("Temporary"); "ok" }
        assertEquals("ok", result); assertEquals(2, attempts)
        attempts = 0
        try { ReadRetry.run { attempts++; throw IOException("Offline") }; fail("Expected failure") } catch (_: IOException) { }
        assertEquals(2, attempts)
    }
    @Test fun authenticationAndTlsFailuresAreNotRetried(): Unit = runBlocking {
        for (error in listOf(SSLException("TLS"), HttpException(Response.error<String>(401, "".toResponseBody())))) {
            var attempts = 0
            try { ReadRetry.run { attempts++; throw error }; fail("Expected failure") } catch (_: Exception) { }
            assertEquals(1, attempts)
        }
    }
    @Test fun cancellationDuringBackoffPreventsRetry(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); var attempts = 0
        val work = launch { ReadRetry.run { attempts++; entered.complete(Unit); throw IOException("Temporary") } }
        entered.await(); work.cancelAndJoin(); assertEquals(1, attempts)
    }
}
