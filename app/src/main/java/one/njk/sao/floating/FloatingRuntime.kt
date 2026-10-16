package one.njk.sao.floating

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** User-requested lifetime, independent of an Activity, a service instance, and picker state. */
@Singleton
class FloatingRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    dispatchers: FloatingDispatchers,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Could not update the floating icon's requested lifetime", error)
    })
    private val changes = Mutex()
    private val requested = booleanPreferencesKey("requested")
    // Device-local run intent must not migrate to another phone through backup/restore.
    private val store = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(SupervisorJob() + dispatchers.io),
        produceFile = { File(context.noBackupFilesDir, "floating_runtime.preferences_pb") },
    )

    suspend fun isRequested() = store.data.first()[requested] ?: false

    /** Application scope lets a completed tap survive the caller's onStop/onDestroy. */
    fun show() = scope.launch {
        changes.withLock {
            if (!Settings.canDrawOverlays(context)) return@withLock
            store.edit { it[requested] = true }
            start(FloatingService.ACTION_SHOW)
        }
    }

    /** Called only by real system lifecycle events, never window/editor events or a timer. */
    fun restore() = scope.launch {
        changes.withLock {
            if (!isRequested() || FloatingSession.active.value || !Settings.canDrawOverlays(context)) return@withLock
            start(FloatingService.ACTION_RESTORE)
        }
    }

    /** Commit dismissal before stopping; a reconnect must not resurrect a dismissed icon. */
    fun stop() = scope.launch {
        changes.withLock {
            try { store.edit { it[requested] = false } }
            finally { context.stopService(Intent(context, FloatingService::class.java)) }
        }
    }

    private fun start(action: String) {
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, FloatingService::class.java).setAction(action))
        } catch (error: IllegalStateException) {
            // A vendor or platform restriction may deny this particular event. No retry loop.
            Log.w(TAG, "System deferred floating icon startup", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "System denied floating icon startup", error)
        }
    }

    private companion object { const val TAG = "FloatingRuntime" }
}
