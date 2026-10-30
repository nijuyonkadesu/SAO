package one.njk.sao.floating

import android.content.*
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import java.util.concurrent.atomic.AtomicLong

/** Synthetic byte-range fixture, excluded from every release/prerelease APK. */
class PlaybackProbeProvider : ContentProvider() {
    private val read=AtomicLong()
    private val opens=AtomicLong()
    private val active=AtomicLong()
    override fun onCreate()=true
    override fun openTypedAssetFile(uri: Uri,mimeTypeFilter: String,opts: Bundle?,signal: CancellationSignal?): AssetFileDescriptor {
        check(opts?.getBoolean(one.njk.sao.source.MediaStreamOptions.STREAMING)==true)
        opens.incrementAndGet(); active.incrementAndGet()
        val thread=HandlerThread("SAO probe reads").apply { start() }
        val file=context!!.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize()=32L*1024*1024
                override fun onRead(offset: Long,size: Int,data: ByteArray): Int {
                    val count=minOf(size.toLong(),onGetSize()-offset).toInt()
                    repeat(count) { data[it]=((offset+it)%251).toByte() }; read.addAndGet(count.toLong()); return count
                }
                override fun onRelease() { active.decrementAndGet(); thread.quitSafely() }
            },Handler(thread.looper))
        return AssetFileDescriptor(file,0,AssetFileDescriptor.UNKNOWN_LENGTH)
    }
    override fun call(method: String,arg: String?,extras: Bundle?)=Bundle().apply {
        if(method=="reset") { read.set(0); opens.set(0) }
        putLong("read",read.get()); putLong("opens",opens.get()); putLong("active",active.get())
    }
    override fun getType(uri: Uri)="application/octet-stream"
    override fun query(uri: Uri,p: Array<out String>?,s: String?,a: Array<out String>?,o: String?): android.database.Cursor?=null
    override fun insert(uri: Uri,v: ContentValues?): Uri?=null
    override fun delete(uri: Uri,s: String?,a: Array<out String>?)=0
    override fun update(uri: Uri,v: ContentValues?,s: String?,a: Array<out String>?)=0
}
