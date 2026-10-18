package one.njk.sao.extension.local

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Explicit network opt-in: fetches a public tokenizer into our shared test folder, never user media. */
class ClipDownloadDeviceTest {
    @Test fun partialTransferResumesAndCorruptArtifactCanBeRepaired()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("verifyModelDownload")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val model=ClipCatalog.lite; val storage=ModelStorage.get(app,model)!!
        require(DocumentsContract.getTreeDocumentId(storage.tree).endsWith("SAO-model-fixtures/CLIP-8bit"))
        assertTrue(storage.ready())
        val tokenizer=model.parts.single { it.path.endsWith("tokenizer.json") }
        val original=storage.document(tokenizer.path)!!
        val backup=storage.input(original).use { it.readBytes() }
        val manager=WorkManager.getInstance(app)
        suspend fun finish()=withTimeout(90_000) {
            while(manager.getWorkInfosForUniqueWork(ClipModels.work(model)).get().any { !it.state.isFinished }) delay(100)
        }
        fun invalidate() { app.getSharedPreferences("context-models",0).edit().remove("verified:${model.id}").commit() }
        try {
            ClipModels.cancel(app,model); finish(); invalidate()
            val partial=DocumentsContract.renameDocument(app.contentResolver,original,"tokenizer.json.partial")!!
            storage.output(partial,tokenizer.size-65536).close()
            ClipModels.download(app,model)
            withTimeout(90_000) { while(!storage.ready()) delay(100) }; finish()
            assertTrue(storage.verify(storage.document(tokenizer.path)!!,tokenizer))
            invalidate()
            val bad=DocumentsContract.renameDocument(app.contentResolver,storage.document(tokenizer.path)!!,"tokenizer.json.partial")!!
            storage.output(bad,0).use { it.write(ByteArray(tokenizer.size.toInt())) }
            ClipModels.download(app,model)
            withTimeout(90_000) { while(manager.getWorkInfosForUniqueWork(ClipModels.work(model)).get().none { it.state==WorkInfo.State.FAILED }) delay(100) }
            assertFalse(storage.ready()); assertNull("Bad completed partial must not poison retries",storage.document("tokenizer.json.partial"))
            ClipModels.download(app,model)
            withTimeout(90_000) { while(!storage.ready()) delay(100) }; finish()
            assertTrue(storage.verify(storage.document(tokenizer.path)!!,tokenizer))
        } finally {
            ClipModels.cancel(app,model); finish()
            storage.document("tokenizer.json.partial")?.let { DocumentsContract.deleteDocument(app.contentResolver,it) }
            storage.output(storage.document(tokenizer.path,true)!!,0).use { it.write(backup) }
            assertTrue(storage.validate())
        }
    }
}
