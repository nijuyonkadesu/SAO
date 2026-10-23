package one.njk.sao.floating

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import one.njk.sao.source.ExtensionService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Explicit test-only component; never advertised as a source or included in release APKs. */
class SourceLifecycleFixtureService : ExtensionService() {
    override fun descriptor() = JSONObject()
    override suspend fun execute(profile: String, operation: String, payload: JSONObject, caller: String): JSONObject {
        if (calls.incrementAndGet() == 1) {
            withContext(Dispatchers.Main) { onDestroy() }
            awaitCancellation()
        }
        return JSONObject().put("ok",true)
    }
    companion object { val calls = AtomicInteger() }
}
