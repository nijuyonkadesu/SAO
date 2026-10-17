package one.njk.sao.floating

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import one.njk.sao.source.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Host-owned, evictable media. Providers supply streams; search results are not cached here. */
@Singleton
class SourceMediaCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val directory = File(context.cacheDir, "source-media")
    private val transfers = Semaphore(3)
    private val locks = Array(32) { Mutex() }
    private val maintenance = Mutex()
    private var prepared = false

    suspend fun get(owner: String, key: String, maxBytes: Long, resource: suspend () -> Uri): File = withContext(Dispatchers.IO) {
        maintenance.withLock {
            if (!prepared) {
                directory.walkTopDown().filter { it.isFile && it.name.endsWith(".pending") }.forEach { it.delete() }
                prepared = true
            }
        }
        val folder = File(directory, digest(owner).take(32)).apply { mkdirs() }
        val file = File(folder, digest(key))
        locks[(file.name.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            var downloaded = false
            if (!file.isFile) transfers.withPermit {
                // Resolving the URI and transferring its bytes share one retry budget.
                ReadRetry.run {
                    val uri = resource()
                    val pending = File.createTempFile("download-", ".pending", folder)
                    try {
                        transfer(uri, pending, maxBytes)
                        currentCoroutineContext().ensureActive()
                        check(pending.renameTo(file)) { "Unable to commit cached media" }
                        downloaded = true
                    } finally { pending.delete() }
                }
            }
            file.setLastModified(System.currentTimeMillis())
            if (downloaded) trim(file)
            file
        }
    }

    private suspend fun transfer(uri: Uri, pending: File, maxBytes: Long) {
        val signal = CancellationSignal()
        val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r", signal))
        try {
            coroutineScope {
                // Closing on a different dispatcher interrupts a blocked pipe read on cancellation.
                val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { signal.cancel(); descriptor.close() }
                }
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        pending.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maxBytes) throw ExtensionFailure("MEDIA_TOO_LARGE")
                                output.write(buffer, 0, count)
                            }
                            descriptor.checkError()
                            if (total == 0L) throw ExtensionFailure("INVALID_RESPONSE")
                        }
                    }
                } finally { closer.cancel() }
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            // Reliable pipes preserve the provider's safe failure code, including HTTP cooldowns.
            val message = error.message.orEmpty()
            val marker = message.indexOf('{')
            val remote = if (marker < 0) null else try { JSONObject(message.substring(marker)) } catch (_: Exception) { null }
            if (remote != null) throw ExtensionFailure(remote.optString("code", "NETWORK"), SourceWire.nullable(remote, "retryAfter"))
            throw error
        } finally { descriptor.close() }
    }

    private suspend fun trim(keep: File) = maintenance.withLock {
        val files = directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".pending") }
            .toList().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= 192L * 1024 * 1024) break
            if (file != keep) {
                val bytes = file.length()
                if (file.delete()) total -= bytes
            }
        }
    }

    suspend fun remove(owner: String) = withContext(Dispatchers.IO) {
        maintenance.withLock { File(directory, digest(owner).take(32)).deleteRecursively() }
        Unit
    }
}
