package one.njk.sao.media

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.function.BooleanSupplier

/** A local, request-owned stream. Frames are straight RGBA; display metadata stays explicit. */
class NativeDecoder private constructor(private var handle: Long, private val ownedInput: Closeable? = null) : Closeable {
    data class Info(val width: Int, val height: Int, val durationUs: Long, val frameCount: Long,
        val colorTransfer: Int, val colorPrimaries: Int, val bytesPerPixel: Int,
        val rotationDegrees: Float, val pixelWidthHeightRatio: Float)
    private val metadata = try { nativeInfo(handle) } catch (error: Throwable) {
        nativeClose(handle); handle = 0; throw error
    }
    val info = Info(metadata[0].toInt(), metadata[1].toInt(), metadata[2], metadata[3], metadata[4].toInt(),
        metadata[5].toInt(), metadata[6].toInt(), metadata[7] / 1000f, metadata[8].toFloat() / metadata[9])
    private val timing = LongArray(2)
    val presentationTimeUs get() = timing[0]
    val frameDurationUs get() = timing[1]
    /** Metadata probe on its own session: consumes up to two decoded frames, without RGBA conversion. */
    @Synchronized fun hasMultipleFrames(): Boolean {
        check(handle != 0L)
        return nativeHasMultipleFrames(handle)
    }

    @Synchronized fun next(rgba: ByteBuffer): Boolean {
        check(handle != 0L)
        require(rgba.isDirect && rgba.capacity().toLong() >= info.width.toLong() * info.height * info.bytesPerPixel)
        return nativeNext(handle, rgba, timing)
    }
    @Synchronized override fun close() { if (handle != 0L) {
        try { nativeClose(handle) } finally { handle = 0; ownedInput?.close() }
    } }
    private external fun nativeInfo(handle: Long): LongArray
    private external fun nativeNext(handle: Long, rgba: ByteBuffer, timing: LongArray): Boolean
    private external fun nativeClose(handle: Long)
    private external fun nativeHasMultipleFrames(handle: Long): Boolean
    companion object {
        @Throws(java.io.IOException::class)
        @JvmOverloads fun open(context: Context, input: File, maxPixels: Long,
            cancelled: BooleanSupplier = BooleanSupplier { Thread.currentThread().isInterrupted }): NativeDecoder {
            check(input.isFile)
            NativeLibrary.load(context)
            return NativeDecoder(nativeOpen(input.absolutePath, maxPixels, cancelled))
        }
        @JvmStatic private external fun nativeOpen(path: String, maxPixels: Long, cancelled: BooleanSupplier): Long
        fun open(context: Context, input: DecoderInput, maxPixels: Long, cancelled: BooleanSupplier): NativeDecoder = try {
            NativeLibrary.load(context)
            NativeDecoder(nativeOpenStream(input, maxPixels, cancelled), input)
        } catch (error: Throwable) { input.close(); throw error }
        @JvmStatic private external fun nativeOpenStream(input: DecoderInput, maxPixels: Long, cancelled: BooleanSupplier): Long
    }
}

/** Usually resolved by the app loader; injected modules may need their public APK's matching ABI. */
private object NativeLibrary {
    private var loaded = false
    @Synchronized fun load(context: Context) {
        if (loaded) return
        try { System.loadLibrary("sao_media") }
        catch (missing: UnsatisfiedLinkError) {
            val owner = context.resources.getResourcePackageName(R.string.sao_media_library_marker)
            val module = context.createPackageContext(owner, 0).applicationInfo
            val installed = File(module.nativeLibraryDir, "libsao_media.so")
            if (installed.isFile) try {
                System.load(installed.absolutePath); loaded = true; return
            } catch (_: UnsatisfiedLinkError) { /* The injected host may use another ABI. */ }
            var failure = missing
            ZipFile(module.sourceDir).use { apk ->
                val directory = File(context.cacheDir, "sao_media_runtime").apply { check(isDirectory || mkdirs()) }
                for (abi in Build.SUPPORTED_ABIS.filter { it.contains("64") == Process.is64Bit() }) {
                    val entry = apk.getEntry("lib/$abi/libsao_media.so") ?: continue
                    val target = File(directory, "decoder-${File(module.sourceDir).lastModified()}-${File(module.sourceDir).length()}-$abi.so")
                    if (!target.isFile) {
                        val pending = File.createTempFile("decoder-", ".pending", directory)
                        try {
                            apk.getInputStream(entry).use { input ->
                                pending.outputStream().use { input.copyTo(it) }
                            }
                            check(pending.setReadOnly() && pending.renameTo(target))
                        } finally { pending.delete() }
                    }
                    try {
                        System.load(target.absolutePath)
                        directory.listFiles()?.filter { it != target && it.name.startsWith("decoder-") && it.name.endsWith(".so") }
                            ?.forEach { it.delete() }
                        loaded = true; return
                    } catch (error: UnsatisfiedLinkError) { failure = error }
                }
            }
            throw failure
        }
        loaded = true
    }
}
