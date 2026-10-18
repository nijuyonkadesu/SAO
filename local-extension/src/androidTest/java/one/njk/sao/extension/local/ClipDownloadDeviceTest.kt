package one.njk.sao.extension.local

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile

/** Explicit network opt-in: fetches public tokenizer bytes only, never user media. */
class ClipDownloadDeviceTest {
    @Test fun partialTransferResumesAndCorruptArtifactCanBeRepaired()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("verifyModelDownload")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ClipModels.ready(app))
        val tokenizer=ClipModels.parts.single { it.path.endsWith("tokenizer.json") }
        val target=ClipModels.file(app,tokenizer.path)
        val backup=File(app.cacheDir,"clip-tokenizer-backup").apply { target.copyTo(this,true) }
        val marker=ClipModels.file(app,"ready")
        val partial=File(target.path+".partial")
        val manager=WorkManager.getInstance(app)
        suspend fun finish()=withTimeout(90_000) {
            while(manager.getWorkInfosForUniqueWork(ClipModels.WORK).get().any { !it.state.isFinished }) delay(100)
        }
        try {
            ClipModels.cancel(app); finish(); marker.delete()
            target.copyTo(partial,true); target.delete()
            RandomAccessFile(partial,"rw").use { it.setLength(tokenizer.size-65536) }
            ClipModels.download(app)
            withTimeout(90_000) { while(!ClipModels.ready(app)) delay(100) }; finish()
            assertTrue(ClipModels.verify(target,tokenizer))
            marker.delete(); target.copyTo(partial,true); target.delete()
            RandomAccessFile(partial,"rw").use { it.write(0) }
            ClipModels.download(app)
            withTimeout(90_000) { while(manager.getWorkInfosForUniqueWork(ClipModels.WORK).get().none { it.state==WorkInfo.State.FAILED }) delay(100) }
            assertFalse(ClipModels.ready(app)); assertFalse("Bad completed partial must not poison every retry",partial.exists())
            ClipModels.download(app)
            withTimeout(90_000) { while(!ClipModels.ready(app)) delay(100) }; finish()
            assertTrue(ClipModels.verify(target,tokenizer))
        } finally {
            ClipModels.cancel(app); finish()
            backup.copyTo(target,true); backup.delete(); partial.delete(); marker.writeText(ClipModels.MODEL)
        }
    }
}
