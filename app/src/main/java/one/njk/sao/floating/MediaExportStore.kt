package one.njk.sao.floating

import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.njk.sao.source.FloatingDispatchers
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Host-owned snapshots. Maintenance runs on explicit exports, never on an idle timer. */
@Singleton
class MediaExportStore @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers()) {
    internal val directory = File(context.filesDir, "floating_exports/v3")
    internal val conversions = Semaphore(1)
    private class Writer(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val writers = mutableMapOf<String, Writer>()
    private val maintenance = Mutex()
    private val handoffs = mutableMapOf<String, Long>()
    private val active = mutableMapOf<String,Int>()
    private var prepared = false
    private val clipboardMarker get() = File(directory.parentFile, ".clipboard")

    internal suspend fun write(key: MediaExportKey, extension: String, build: suspend (File) -> Unit): File = withContext(dispatchers.io) {
        val name = key.filename(extension)
        val file = File(directory, name)
        val writer = maintenance.withLock {
            directory.mkdirs()
            if (!prepared) {
                directory.parentFile!!.walkTopDown().filter { it.isFile && it.name.endsWith(".pending") }.forEach { it.delete() }
                prepared = true
            }
            writers.getOrPut(name) { Writer() }.also { it.users++ }
        }
        try {
            writer.mutex.withLock {
                maintenance.withLock { retainLocked(file) }
                try {
                    if (!file.isFile) {
                        val staging = File.createTempFile("export-", ".pending", directory)
                        try {
                            build(staging)
                            currentCoroutineContext().ensureActive()
                            check(staging.length() > 0 && staging.renameTo(file)) { "Unable to save export" }
                        } finally { staging.delete() }
                    }
                    file.setLastModified(System.currentTimeMillis())
                    maintenance.withLock {
                        handoffs[file.absolutePath] = android.os.SystemClock.elapsedRealtime() + HANDOFF_MS
                        trimLocked()
                    }
                    file
                } finally { withContext(NonCancellable) { maintenance.withLock { releaseLocked(file) } } }
            }
        } finally { withContext(NonCancellable) { maintenance.withLock {
            writer.users--
            if (writer.users == 0) writers.remove(name)
        } } }
    }

    internal suspend fun <T> using(file: File, block: suspend () -> T): T {
        maintenance.withLock { check(file.isFile); retainLocked(file) }
        try { return block() }
        finally { withContext(NonCancellable) { maintenance.withLock { releaseLocked(file) } } }
    }
    private fun retainLocked(file: File) { active[file.absolutePath]=(active[file.absolutePath] ?: 0)+1 }
    private fun releaseLocked(file: File) {
        val remaining=active.getValue(file.absolutePath)-1
        if(remaining==0) active.remove(file.absolutePath) else active[file.absolutePath]=remaining
    }

    /** A validated media revision already prepared here needs no source read or codec probe. */
    internal suspend fun cached(key: MediaExportKey,extension: String): File? = withContext(dispatchers.io) {
        maintenance.withLock {
            val file=File(directory,key.filename(extension))
            if(!file.isFile || file.length()==0L) return@withLock null
            file.setLastModified(System.currentTimeMillis())
            handoffs[file.absolutePath]=android.os.SystemClock.elapsedRealtime()+HANDOFF_MS
            file
        }
    }

    /** One persisted clipboard pin, written only when SAO publishes a new clip. */
    suspend fun clipboard(file: File?) = withContext(dispatchers.io) { maintenance.withLock {
        clipboardMarker.parentFile!!.mkdirs()
        val pending = File(clipboardMarker.parentFile, ".clipboard.pending")
        try {
            if (file == null) clipboardMarker.delete() else {
                require(file.canonicalPath.startsWith(directory.parentFile!!.canonicalPath + File.separator))
                pending.writeText(file.canonicalPath)
                check(pending.renameTo(clipboardMarker))
            }
        } finally { pending.delete() }
    } }

    private fun trimLocked() {
        val now = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        handoffs.entries.removeAll { it.value <= elapsed }
        val clipboard = clipboardMarker.takeIf { it.isFile }?.readText()
        val roots = listOf(directory.parentFile!!, File(context.cacheDir, "share_cache/home"))
        val files = roots.flatMap { root -> root.walkTopDown().filter {
            it.isFile && !it.name.startsWith('.') && !it.name.endsWith(".pending")
        }.toList() }.sortedBy { it.lastModified() }
        var bytes = files.sumOf(File::length)
        for (file in files) {
            if (file.absolutePath in active || file.absolutePath in handoffs || file.canonicalPath == clipboard) continue
            // A process restart loses short handoff leases. Retain a bounded grace period on disk.
            if (now - file.lastModified() < HANDOFF_MS) continue
            if (bytes <= MAX_BYTES && now - file.lastModified() <= MAX_AGE_MS) continue
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    internal fun exported(file: File, mime: String) = ExportedMedia(
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), mime, file)

    companion object {
        internal const val MAX_BYTES = 192L * 1024 * 1024
        internal const val HANDOFF_MS = 10L * 60 * 1000
        internal const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        /** IO copies must also stop when a newer selection cancels the caller. */
        internal suspend fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }
}
