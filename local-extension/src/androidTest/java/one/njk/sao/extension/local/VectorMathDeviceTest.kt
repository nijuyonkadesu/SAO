package one.njk.sao.extension.local

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VectorMathDeviceTest {
    @Test fun nativeScoresMatchDoublePrecisionReferenceAndReleaseSafely() {
        val query=FloatArray(512) { ((it*37)%503-251)/503f }
        VectorMath.Scorer(query).use { scorer ->
            repeat(25) { sample ->
                val vector=FloatArray(512) { ((it*17+sample*29)%509-254)/509f }
                val bytes=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(vector) }.array()
                val expected=query.indices.sumOf { query[it].toDouble()*vector[it] }
                assertEquals(expected,scorer.score(bytes),1e-12)
            }
            assertTrue(scorer.score(ByteArray(1)).isNaN())
        }
        val scorer=VectorMath.Scorer(query); scorer.close(); scorer.close()
        try { scorer.score(ByteArray(2048)); fail("Used released scorer") } catch(_: IllegalStateException) { }
    }
}
