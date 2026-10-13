package one.njk.sao.floating

import org.junit.Assert.*
import org.junit.Test

class LoopingChoiceCursorTest {
    @Test fun draggingWrapsBothWaysAndCanReverseAcrossSeveralLoops() {
        val cursor = LoopingChoiceCursor(5, 0, 48f)
        assertTrue(cursor.move(-48f)); assertEquals(4, cursor.index)
        assertTrue(cursor.move(48f)); assertEquals(1, cursor.index)
        cursor.move(48f * 11); assertEquals(1, cursor.index)
        cursor.move(-48f * 12); assertEquals(3, cursor.index)
        cursor.move(0f); assertEquals(0, cursor.index)
        assertEquals(4, cursor.neighbor(-1)); assertEquals(1, cursor.neighbor(1))
    }
    @Test fun SmallMovementOnlyTranslatesAndDoesNotChangeTheChoice() {
        val cursor = LoopingChoiceCursor(5, 2, 48f)
        assertFalse(cursor.move(10f)); assertEquals(2, cursor.index); assertEquals(-10f, cursor.translation, 0f)
        assertTrue(cursor.move(30f)); assertEquals(3, cursor.index); assertEquals(18f, cursor.translation, 0f)
        assertFalse(cursor.move(Float.NaN)); assertEquals(3, cursor.index)
    }
    @Test fun aSingleAvailableChoiceStaysStableThroughLoops() {
        val cursor = LoopingChoiceCursor(1, 0, 64f)
        assertFalse(cursor.move(640f)); assertEquals(0, cursor.index)
        assertFalse(cursor.move(-192f)); assertEquals(0, cursor.neighbor(-100))
    }
}
