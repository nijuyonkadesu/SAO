package one.njk.sao.floating

import kotlin.math.roundToInt

/** A finite list with an unbounded drag position. No UI or source reads during preview. */
internal class LoopingChoiceCursor(private val count: Int, private val initial: Int, val step: Float) {
    init { require(count > 0); require(initial in 0 until count); require(step > 0 && step.isFinite()) }
    var index: Int = initial
        private set
    var translation: Float = 0f
        private set
    fun move(distanceUp: Float): Boolean {
        if (!distanceUp.isFinite()) return false
        val steps = (distanceUp / step).roundToInt()
        val next = at(steps)
        translation = steps * step - distanceUp
        val changed = index != next
        index = next
        return changed
    }
    fun neighbor(offset: Int) = Math.floorMod(index + offset, count)
    private fun at(offset: Int) = Math.floorMod(initial.toLong() + offset, count.toLong()).toInt()
}
