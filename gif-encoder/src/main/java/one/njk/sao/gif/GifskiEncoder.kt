package one.njk.sao.gif

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Single producer, with a concurrent cancellation signal. Close always joins native workers. */
class GifskiEncoder(output: File, maxBytes: Long): Closeable {
    private var handle: Long = nativeCreate(output.absolutePath,maxBytes).also {
        if(it==0L) throw IOException("Unable to create GIF encoder")
    }
    private var finished=false

    fun add(frame: Int,width: Int,height: Int,rowBytes: Int,rgba: ByteBuffer,seconds: Double) {
        check(!finished && handle!=0L)
        require(rgba.isDirect && width in 1..65535 && height in 1..65535 && rowBytes%4==0 && rowBytes>=width.toLong()*4 &&
            rgba.capacity().toLong()>=(height-1L)*rowBytes+width*4L && seconds.isFinite() && seconds>=0)
        checkResult(nativeAdd(handle,frame,width,height,rowBytes,rgba,seconds))
    }
    fun finish() {
        check(!finished && handle!=0L)
        // The context stays alive until close(), so cancellation remains safe while joining.
        finished=true
        checkResult(nativeFinish(handle))
    }
    @Synchronized fun cancel() { if(handle!=0L) nativeCancel(handle) }
    override fun close() {
        if(handle==0L) return
        try { if(!finished) { cancel(); finished=true; nativeFinish(handle) } }
        finally { synchronized(this) { nativeDestroy(handle); handle=0 } }
    }
    private fun checkResult(code: Int) { if(code!=0) throw IOException("GIF encoder failed ($code)") }
    private external fun nativeCreate(path: String,maxBytes: Long): Long
    private external fun nativeAdd(handle: Long,frame: Int,width: Int,height: Int,rowBytes: Int,rgba: ByteBuffer,seconds: Double): Int
    private external fun nativeFinish(handle: Long): Int
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)
    companion object { init { System.loadLibrary("sao_gif") } }
}
