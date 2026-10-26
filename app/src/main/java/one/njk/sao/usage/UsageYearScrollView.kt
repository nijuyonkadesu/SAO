package one.njk.sao.usage

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.sign

/** Let the year strip consume a drag before its carousel, yielding only at the relevant edge. */
@SuppressLint("ViewConstructor") // Owned by a calendar card, with its current interaction policy.
internal class UsageYearScrollView(context: Context,private val interactive: ()->Boolean): HorizontalScrollView(context) {
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var pointer=-1
    private var downX=0f
    private var downY=0f
    private var lastX=0f
    private var horizontal: Boolean?=null
    init { isHorizontalScrollBarEnabled=false; overScrollMode=OVER_SCROLL_NEVER }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            pointer=event.getPointerId(0); downX=event.x; downY=event.y; lastX=event.x; horizontal=null
            parent?.requestDisallowInterceptTouchEvent(interactive())
        }
        val handled=super.dispatchTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index=event.findPointerIndex(pointer)
                if(index<0 || !interactive()) parent?.requestDisallowInterceptTouchEvent(false)
                else {
                    val x=event.getX(index)
                    val dx=x-downX; val dy=event.getY(index)-downY
                    if(horizontal==null && maxOf(abs(dx),abs(dy))>slop) horizontal=abs(dx)>abs(dy)
                    val direction=-(x-lastX).sign.toInt()
                    // Run after the child scrolls, so an edge reached by this move can hand
                    // the next move to RecyclerView. Native cancellation transfers the drag.
                    parent?.requestDisallowInterceptTouchEvent(when(horizontal) {
                        null -> true
                        false -> false
                        true -> direction==0 || canScrollHorizontally(direction)
                    })
                    lastX=x
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if(event.getPointerId(event.actionIndex)==pointer) {
                val next=if(event.actionIndex==0) 1 else 0
                pointer=event.getPointerId(next); downX=event.getX(next); lastX=downX; downY=event.getY(next)
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                pointer=-1; horizontal=null; parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return handled
    }
    override fun onDetachedFromWindow() {
        parent?.requestDisallowInterceptTouchEvent(false)
        super.onDetachedFromWindow()
    }
}
