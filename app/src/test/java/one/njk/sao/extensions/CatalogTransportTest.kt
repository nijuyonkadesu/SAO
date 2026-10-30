package one.njk.sao.extensions

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Local transport faults only; no real catalogs, accounts or APK installation. */
class CatalogTransportTest {
    @Test fun unavailableFirstAddressStillReachesTheOtherAddressWithoutRepeatingTheDownload(): Unit=runBlocking {
        val server=MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"),0) }
        try {
            server.enqueue(MockResponse().setBody("download fixture"))
            val client=CatalogHttp.defaultClient().newBuilder().proxy(Proxy.NO_PROXY)
                .dns(object: Dns {
                    override fun lookup(hostname: String)=listOf(InetAddress.getByName("127.0.0.2"),InetAddress.getByName("127.0.0.1"))
                })
                .connectTimeout(500,TimeUnit.MILLISECONDS).callTimeout(5,TimeUnit.SECONDS).build()
            val url=server.url("/fixture.apk").newBuilder().host("catalog.fixture.invalid").build()
            val bytes=CatalogHttp(client).receive(url.toString()) { input,_ -> input.readBytes() }
            assertEquals("download fixture",bytes.toString(Charsets.UTF_8))
            assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun interruptedResponseBodyIsNotDownloadedAgainAutomatically(): Unit=runBlocking {
        val server=MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("x".repeat(128*1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val transport=CatalogHttp(CatalogHttp.defaultClient())
            try {
                transport.receive(server.url("/fixture.apk").toString()) { input,_ -> input.readBytes() }
                fail("Truncated body was accepted")
            } catch(_: IOException) { }
            assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }
}
