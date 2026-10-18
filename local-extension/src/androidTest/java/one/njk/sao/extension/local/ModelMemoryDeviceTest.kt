package one.njk.sao.extension.local

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class ModelMemoryDeviceTest {
    @Test fun mappingUsesDescriptorAndUnmapsDeterministicallyIncludingFailurePaths() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(app.cacheDir,"mapping-${UUID.randomUUID()}").apply { writeBytes(byteArrayOf(3,5,7,11)) }
        fun mapped()=File("/proc/self/maps").useLines { lines -> lines.any { file.name in it } }
        try {
            assertFalse(mapped())
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                try {
                    ModelMemory.Mapping(descriptor.fd).use { mapping ->
                        val bytes=mapping.bytes
                        assertTrue(bytes.isDirect); assertTrue(bytes.isReadOnly)
                        assertEquals(4,bytes.remaining()); assertEquals(7,bytes.get(2).toInt())
                        assertTrue(mapped()); throw IOException("Synthetic session creation failure")
                    }
                } catch(_: IOException) { }
                assertFalse("Mapping remained after failure",mapped())
                val mapping=ModelMemory.Mapping(descriptor.fd); assertTrue(mapped())
                mapping.close(); mapping.close(); assertFalse("Mapping remained after close",mapped())
            }
        } finally { file.delete() }
    }
    @Test fun nonSeekableDescriptorsAreRejectedWithoutLeakingResources() {
        ParcelFileDescriptor.createPipe().let { pipe ->
            pipe[0].use { input -> pipe[1].use {
                try { ModelMemory.Mapping(input.fd).close(); fail("Accepted a non-file model") }
                catch(_: IOException) { }
            } }
        }
    }
}
