package one.njk.sao.extension.local

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Same FP32 encoders, tensor layout and normalization as Immich's default CLIP pipeline. */
class ClipEncoder(context: Context, private val visual: Boolean) : AutoCloseable {
    private val tokenizer=if(visual) null else ClipTokenizer(ClipModels.file(context,"textual/tokenizer.json"))
    private val environment=runtimeEnvironment
    private val session=OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
        options.addConfigEntry("session.intra_op.allow_spinning","0")
        environment.createSession(ClipModels.file(context,if(visual) "visual/model.onnx" else "textual/model.onnx").path,options)
    }
    suspend fun text(value: String): FloatArray {
        check(!visual)
        OnnxTensor.createTensor(environment,IntBuffer.wrap(tokenizer!!.encode(value)),longArrayOf(1,77)).use { return infer("text",it) }
    }
    suspend fun image(context: Context, asset: LocalAsset): FloatArray {
        check(visual)
        val bitmap=MediaBitmaps.read(context,Uri.parse(asset.uri),asset.mime,2048,Color.BLACK)
        val input=try { withContext(Dispatchers.Default) { pixels(bitmap) } } finally { bitmap.recycle() }
        currentCoroutineContext().ensureActive()
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,3,224,224)).use { return infer("image",it) }
    }
    private suspend fun infer(name: String, input: OnnxTensor): FloatArray=coroutineScope {
        OrtSession.RunOptions().use { options ->
            val stop=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { options.setTerminate(true) }
            }
            try {
                ensureActive()
                session.run(mapOf(name to input),options).use { result ->
                    val values=(result[0] as OnnxTensor).floatBuffer
                    val vector=FloatArray(values.remaining()); values.get(vector)
                    require(vector.size==512 && vector.all { it.isFinite() })
                    val length=sqrt(vector.sumOf { it.toDouble()*it })
                    require(length>0)
                    FloatArray(512) { (vector[it]/length).toFloat() }
                }
            } catch(error: OrtException) { ensureActive(); throw error }
            finally { withContext(NonCancellable) { stop.cancelAndJoin() } }
        }
    }
    override fun close() { session.close() }

    companion object {
        private val runtimeEnvironment by lazy { OrtEnvironment.getEnvironment().apply { setTelemetry(false) } }
        /** Antialiased bicubic shortest-edge resize and center crop, without a huge panoramic intermediate. */
        internal fun pixels(bitmap: Bitmap): FloatArray {
            val width=bitmap.width; val height=bitmap.height
            val scaledWidth=if(width<height) 224 else (width.toDouble()/height*224).toInt()
            val scaledHeight=if(width<height) (height.toDouble()/width*224).toInt() else 224
            val horizontal=weights(width,scaledWidth,(scaledWidth-224)/2)
            val vertical=weights(height,scaledHeight,(scaledHeight-224)/2)
            val source=IntArray(width*height); bitmap.getPixels(source,0,width,0,0,width,height)
            val intermediate=IntArray(height*224*3)
            for(y in 0 until height) for(x in 0 until 224) for(channel in 0..2) {
                val filter=horizontal[x]; var sum=0.0
                for(k in filter.values.indices) sum+=((source[y*width+filter.start+k] shr (16-channel*8)) and 255)*filter.values[k]
                intermediate[(y*224+x)*3+channel]=floor(sum+0.5).toInt().coerceIn(0,255)
            }
            val mean=floatArrayOf(.48145466f,.4578275f,.40821073f)
            val std=floatArrayOf(.26862954f,.26130258f,.27577711f)
            val result=FloatArray(3*224*224)
            for(y in 0 until 224) for(x in 0 until 224) for(channel in 0..2) {
                val filter=vertical[y]; var sum=0.0
                for(k in filter.values.indices) sum+=intermediate[((filter.start+k)*224+x)*3+channel]*filter.values[k]
                val value=floor(sum+0.5).toInt().coerceIn(0,255)/255f
                result[channel*224*224+y*224+x]=value*(1f/std[channel])-mean[channel]/std[channel]
            }
            return result
        }
        private data class Filter(val start: Int,val values: DoubleArray)
        private fun weights(input: Int,output: Int,offset: Int): List<Filter> {
            val scale=input.toDouble()/output; val support=max(1.0,scale)*2
            return List(224) { position ->
                val center=(position+offset+.5)*scale
                val start=(center-support+.5).toInt().coerceAtLeast(0)
                val end=(center+support+.5).toInt().coerceAtMost(input)
                val weights=DoubleArray(end-start) { k ->
                    val x=abs((start+k-center+.5)/max(1.0,scale))
                    when { x<1 -> (1.5*x-2.5)*x*x+1; x<2 -> ((-.5*x+2.5)*x-4)*x+2; else -> 0.0 }
                }
                val sum=weights.sum(); weights.indices.forEach { weights[it]/=sum }
                Filter(start,weights)
            }
        }
    }
}

/** Reuse model weights, never query results. Release native memory after idle or service destruction. */
class ClipTextSession(private val context: Context) {
    private val mutex=Mutex()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val closed=AtomicBoolean(false)
    private var encoder: ClipEncoder?=null
    private var release: Job?=null
    suspend fun encode(text: String): FloatArray=mutex.withLock {
        check(!closed.get()); release?.cancel()
        try {
            currentCoroutineContext().ensureActive()
            val engine=encoder ?: ClipEncoder(context,false).also { encoder=it }
            engine.text(text)
        } finally {
            release=scope.launch { delay(30_000); mutex.withLock { encoder?.close(); encoder=null } }
        }
    }
    fun close() {
        if(!closed.compareAndSet(false,true)) return
        release?.cancel()
        scope.launch { try { mutex.withLock { encoder?.close(); encoder=null } } finally { scope.cancel() } }
    }
}
