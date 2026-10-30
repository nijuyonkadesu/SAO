package one.njk.sao.floating

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import one.njk.sao.source.MediaStreamOptions
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Cancellable provider reads. A provider's seekable descriptor negotiates ranges in its own APK. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ProviderMediaDataSource(private val context: Context, private val owner: Job) : BaseDataSource(false) {
    private val descriptor = AtomicReference<AssetFileDescriptor?>()
    private val input = AtomicReference<FileInputStream?>()
    private val remote = AtomicReference<one.njk.sao.source.IMediaReadControl?>()
    private var cancellation: Job? = null
    private var signal: CancellationSignal? = null
    private var address: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false
    override fun getUri() = address
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        address = dataSpec.uri
        val cancel = CancellationSignal().also { signal = it }
        cancellation = CoroutineScope(owner + Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { cancel.cancel(); releaseDescriptors() }
        }
        try {
            owner.ensureActive()
            val options = Bundle().apply {
                putBoolean(MediaStreamOptions.STREAMING, true)
                putLong(MediaStreamOptions.POSITION, dataSpec.position)
            }
            val asset = context.contentResolver.openTypedAssetFileDescriptor(dataSpec.uri, "*/*", options, cancel)
                ?: throw IOException("Media stream is unavailable")
            descriptor.set(asset)
            remote.set(one.njk.sao.source.IMediaReadControl.Stub.asInterface(asset.extras?.getBinder(MediaStreamOptions.CONTROL)))
            owner.ensureActive()
            val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(asset.parcelFileDescriptor).also(input::set)
            val length = if (asset.declaredLength >= 0) asset.declaredLength else
                runCatching { stream.channel.size() }.getOrDefault(0).takeIf { it > 0 } ?: C.LENGTH_UNSET.toLong()
            if (length >= 0 && dataSpec.position > length) throw EOFException("Media position is out of range")
            try { stream.channel.position(asset.startOffset + dataSpec.position) }
            catch (error: IOException) {
                if (asset.startOffset + dataSpec.position != 0L) throw IOException("This source cannot seek", error)
            }
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else
                if (length >= 0) length - dataSpec.position else C.LENGTH_UNSET.toLong()
            owner.ensureActive()
            started = true; transferStarted(dataSpec)
            return remaining
        } catch (error: Exception) { close(); throw IOException("Could not open media stream", error) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        owner.ensureActive()
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = requireNotNull(input.get()).read(buffer, offset,
            if (remaining < 0) length else minOf(length.toLong(), remaining).toInt())
        if (count < 0) {
            descriptor.get()?.parcelFileDescriptor?.checkError()
            if (remaining > 0) throw EOFException("Media stream ended early")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining >= 0) remaining -= count
        bytesTransferred(count)
        return count
    }
    private fun releaseDescriptors() {
        runCatching { remote.getAndSet(null)?.cancel() }
        runCatching { input.getAndSet(null)?.close() }
        runCatching { descriptor.getAndSet(null)?.close() }
    }
    override fun close() {
        signal?.cancel(); signal = null
        cancellation?.cancel(); cancellation = null
        releaseDescriptors()
        address = null
        if (started) { started = false; transferEnded() }
    }
}
