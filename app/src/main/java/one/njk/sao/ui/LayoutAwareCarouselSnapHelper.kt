package one.njk.sao.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.carousel.CarouselSnapHelper

/** Material invalidates its keylines when size/strategy changes, before its next layout builds them. */
internal class LayoutAwareCarouselSnapHelper(private val view: RecyclerView,private val ready: ()->Boolean={true}): CarouselSnapHelper(false) {
    private fun laidOut()=ready() && view.isLaidOut && !view.isLayoutRequested && !view.isComputingLayout
    override fun findSnapView(manager: RecyclerView.LayoutManager): View?=if(laidOut()) super.findSnapView(manager) else null
    override fun findTargetSnapPosition(manager: RecyclerView.LayoutManager,x: Int,y: Int)=
        if(laidOut()) super.findTargetSnapPosition(manager,x,y) else RecyclerView.NO_POSITION
    override fun calculateDistanceToFinalSnap(manager: RecyclerView.LayoutManager,target: View): IntArray=
        if(laidOut()) (super.calculateDistanceToFinalSnap(manager,target) ?: intArrayOf(0,0)) else intArrayOf(0,0)
}
