package one.njk.sao.floating

import one.njk.sao.source.*

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class AppTransition(val packageName: String, val activity: String, val resumed: Boolean)

object ForegroundEvents {
    fun resolve(events: List<AppTransition>): String? {
        val active = linkedMapOf<String, String>()
        events.forEach {
            val key = "${it.packageName}/${it.activity}"
            if (it.resumed) active[key] = it.packageName else active.remove(key)
        }
        return active.values.distinct().singleOrNull()
    }
}

@Singleton
class ForegroundAppDetector @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers()) {
    private val queries = Mutex()
    private var since = 0L
    private val active = linkedMapOf<String, String>()
    fun hasAccess(): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    suspend fun capturePackage(): String? = withContext(dispatchers.io) { queries.withLock {
        if (!hasAccess()) return@withLock null
        try {
            val now = System.currentTimeMillis()
            val events = context.getSystemService(UsageStatsManager::class.java)
                .queryEvents(if (since == 0L) now - 24 * 60 * 60 * 1000L else since, now) ?: return@withLock null
            val event = UsageEvents.Event()
            since = now

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> active["${event.packageName}/${event.className}"] = event.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> active.remove("${event.packageName}/${event.className}")
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> active.clear()
                }
            }
            val pkg = active.values.distinct().singleOrNull().takeUnless { it == context.packageName }
            pkg
        } catch (_: SecurityException) { null }
    } }
}
