package one.njk.sao.extension.moeview

import android.content.Intent
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.njk.sao.moeview.data.Board
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** Explicit public-site probe. It never clicks or automates the challenge or uses personal credentials. */
class BoardVerificationDeviceTest {
    @Test fun visibleVerificationAndNativeHandoff(): Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val app=instrumentation.targetContext
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("verifyBoard")=="true")
        ActivityScenario.launch<BoardVerificationActivity>(Intent(app,BoardVerificationActivity::class.java).putExtra("board",Board.DANBOORU.name)).use { scenario ->
            var cookies=""; var agent=""
            val until=SystemClock.elapsedRealtime()+15_000
            do {
                scenario.onActivity { activity ->
                    val web=activity.findViewById<WebView>(R.id.web)
                    assertTrue(web.settings.javaScriptEnabled); assertFalse(web.settings.allowFileAccess); assertFalse(web.settings.allowContentAccess)
                    cookies=MoeviewAuthentication.cloudflareCookies(CookieManager.getInstance().getCookie(Board.DANBOORU.directBase).orEmpty())
                    agent=web.settings.userAgentString
                }
                if(cookies.contains("cf_clearance=")) break
                SystemClock.sleep(250)
            } while(SystemClock.elapsedRealtime()<until)
            var result="NO_CLEARANCE"
            if(cookies.contains("cf_clearance=")) {
                val api=MoeviewRuntime.get(app).factory(MoeviewConfig(board=Board.DANBOORU,clearance=cookies,browserAgent=agent))
                result=try { api.search(Board.DANBOORU,limit=1); "OK" } catch(error: Exception) { MoeviewRuntime.failure(error).code }
                finally { api.close(); api.okHttpClient.dispatcher.executorService.shutdown() }
            }
            println("Cloudflare probe: clearancePresent=${cookies.contains("cf_clearance=")}, nativeResult=$result")
            java.io.File(app.cacheDir,"moeview-verification-report").writeText("clearancePresent=${cookies.contains("cf_clearance=")}; nativeResult=$result")
            if(InstrumentationRegistry.getArguments().getString("visualPause")=="true") {
                val ready=java.io.File(app.cacheDir,"moeview-visual-verification").apply { writeText("ready") }
                val resume=java.io.File(app.cacheDir,"moeview-visual-continue").apply { delete() }
                try {
                    val deadline=SystemClock.elapsedRealtime()+50_000
                    while(!resume.exists() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
                } finally { ready.delete(); resume.delete() }
            }
        }
    }
}
