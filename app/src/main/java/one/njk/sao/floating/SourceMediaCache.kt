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
    private val readers = mutableMapOf<String, Int>()
    private val retired = mutableSetOf<String>()

    suspend fun <T> withFile(owner: String, key: String, maxBytes: Long, resource: suspend () -> Uri,
        block: suspend (File) -> T): T {
        val path = File(File(directory, digest(owner).take(32)), digest(key)).absolutePath
        maintenance.withLock { readers[path] = (readers[path] ?: 0) + 1 }
        try { return block(get(owner, key, maxBytes, resource)) }
        finally { withContext(NonCancellable + Dispatchers.IO) { maintenance.withLock {
            val remaining = readers.getValue(path) - 1
            if (remaining == 0) readers.remove(path) else readers[path] = remaining
            val folder = File(path).parentFile!!
            if (folder.absolutePath in retired && readers.keys.none { it.startsWith(folder.absolutePath + File.separator) }) {
                folder.deleteRecursively(); retired.remove(folder.absolutePath)
            }
        } } }
    }

    private suspend fun get(owner: String, key: String, maxBytes: Long, resource: suspend () -> Uri): File = withContext(Dispatchers.IO) {
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
        val opened = java.util.concurrent.atomic.AtomicReference<ParcelFileDescriptor?>()
        try {
            coroutineScope {
                // Register before open: cancellation must reach a provider blocked in openFile too.
                val closer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { signal.cancel(); opened.get()?.close() }
                }
                try {
                    val descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r", signal)).also(opened::set)
                    currentCoroutineContext().ensureActive()
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
        } finally { opened.get()?.close() }
    }

    private suspend fun trim(keep: File) = maintenance.withLock {
        val files = directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".pending") }
            .toList().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            // The remaining 64 MiB budget belongs to progressive playback spans.
            if (total <= 128L * 1024 * 1024) break
            if (file != keep && file.absolutePath !in readers) {
                val bytes = file.length()
                if (file.delete()) total -= bytes
            }
        }
    }

    suspend fun remove(owner: String) = withContext(Dispatchers.IO) {
        PlaybackCache.remove(context, owner)
        maintenance.withLock {
            val folder = File(directory, digest(owner).take(32))
            if (readers.keys.any { it.startsWith(folder.absolutePath + File.separator) }) retired.add(folder.absolutePath)
            else folder.deleteRecursively()
        }
        Unit
    }
}
