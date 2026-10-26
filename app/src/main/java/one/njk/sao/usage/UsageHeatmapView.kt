package one.njk.sao.usage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import one.njk.sao.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal fun Context.usageHeatColors(): List<Int> {
    val surface=usageColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
    val primary=usageColor(androidx.appcompat.R.attr.colorPrimary)
    return listOf(surface)+listOf(.22f,.45f,.70f,1f).map { ColorUtils.blendARGB(surface,primary,it) }
}

/** Compact chart cells; virtual date nodes and the date picker provide precise accessible selection. */
@SuppressLint("ViewConstructor") // Report data/callback supplied programmatically.
internal class UsageHeatmapView(context: Context,val year: Int,private val days: Map<Long,Long>,
    private val selected: LocalDate,private val today: LocalDate,private val firstWeekday: DayOfWeek,
    private val onDate: (LocalDate)->Unit): View(context) {
    private val density=resources.displayMetrics.density
    private val locale=resources.configuration.locales[0]
    private val first=LocalDate.of(year,1,1)
    private val end=minOf(first.plusYears(1),today.plusDays(1))
    private val gridStart=first.with(TemporalAdjusters.previousOrSame(firstWeekday))
    private val columns=((ChronoUnit.DAYS.between(gridStart,end)+6)/7).toInt()
    private val colors=context.usageHeatColors()
    private val intensity=UsageIntensity(days.values)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val pitch=16*density
    private val side=12*density
    private val gutter=maxOf(28*density,context.usageSp(11f)*2)
    private val top=context.usageSp(11f)+12*density
    private val rtl get()=layoutDirection==LAYOUT_DIRECTION_RTL
    private var pressed=ExploreByTouchHelper.INVALID_ID
    private val accessibility=object: ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float,y: Float)=hit(x,y)
        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            for(day in 0 until first.lengthOfYear()) if(!first.plusDays(day.toLong()).isAfter(today)) ids.add(day)
        }
        override fun onPopulateNodeForVirtualView(id: Int,node: AccessibilityNodeInfoCompat) {
            val date=first.plusDays(id.toLong())
            node.contentDescription=context.getString(R.string.usage_date_activity,
                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),context.actionCount(days[date.toEpochDay()] ?: 0))
            node.setBoundsInParent(bounds(date)); node.className="android.widget.Button"
            node.isSelected=date==selected; node.isClickable=true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }
        override fun onPerformActionForVirtualView(id: Int,action: Int,args: Bundle?): Boolean {
            if(action!=AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            onDate(first.plusDays(id.toLong())); sendEventForVirtualView(id,AccessibilityEvent.TYPE_VIEW_CLICKED); return true
        }
        override fun onVirtualViewKeyboardFocusChanged(id: Int,hasFocus: Boolean) {
            if(hasFocus) requestRectangleOnScreen(bounds(first.plusDays(id.toLong())),false)
            invalidate()
        }
    }
    init { isFocusable=true; ViewCompat.setAccessibilityDelegate(this,accessibility) }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        setMeasuredDimension((gutter+columns*pitch+4*density).toInt(),(top+7*pitch+4*density).toInt())
    }
    internal fun bounds(date: LocalDate): Rect {
        val offset=ChronoUnit.DAYS.between(gridStart,date).toInt()
        val column=if(rtl) columns-1-offset/7 else offset/7
        val x=gutter+column*pitch; val y=top+(offset%7)*pitch
        return Rect(x.toInt(),y.toInt(),(x+side).toInt(),(y+side).toInt())
    }
    private fun hit(x: Float,y: Float): Int {
        if(x<gutter || y<top || y>=top+7*pitch) return ExploreByTouchHelper.INVALID_ID
        val column=((x-gutter)/pitch).toInt()
        if(column !in 0 until columns) return ExploreByTouchHelper.INVALID_ID
        val date=gridStart.plusDays(((if(rtl) columns-1-column else column)*7+((y-top)/pitch).toInt()).toLong())
        return if(date<first || date>=end || date>today) ExploreByTouchHelper.INVALID_ID else ChronoUnit.DAYS.between(first,date).toInt()
    }
    override fun onDraw(canvas: Canvas) {
        paint.textSize=context.usageSp(11f); paint.style=Paint.Style.FILL
        paint.color=context.usageColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        for(weekday in listOf(0,2,4)) {
            val label=firstWeekday.plus(weekday.toLong()).getDisplayName(TextStyle.NARROW,locale)
            paint.textAlign=Paint.Align.CENTER
            canvas.drawText(label,gutter/2,top+weekday*pitch+side/2-(paint.ascent()+paint.descent())/2,paint)
        }
        for(month in 1..12) {
            val date=LocalDate.of(year,month,1)
            if(date>=end) continue
            val rect=bounds(date)
            paint.textAlign=if(rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.drawText(date.month.getDisplayName(TextStyle.SHORT,locale),if(rtl) rect.right.toFloat() else rect.left.toFloat(),context.usageSp(11f),paint)
        }
        for(day in 0 until first.lengthOfYear()) {
            val date=first.plusDays(day.toLong())
            if(date>today) continue
            val rect=bounds(date)
            paint.color=colors[intensity.level(days[date.toEpochDay()] ?: 0)]
            canvas.drawRoundRect(rect.left.toFloat(),rect.top.toFloat(),rect.right.toFloat(),rect.bottom.toFloat(),3*density,3*density,paint)
            if(date==selected || day==accessibility.keyboardFocusedVirtualViewId) {
                paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f*density
                paint.color=context.usageColor(com.google.android.material.R.attr.colorOnSurface)
                canvas.drawRoundRect(rect.left-density,rect.top-density,rect.right+density,rect.bottom+density,4*density,4*density,paint)
                paint.style=Paint.Style.FILL
            }
        }
    }
    override fun dispatchHoverEvent(event: MotionEvent)=accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent)=accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    override fun onFocusChanged(gain: Boolean,direction: Int,previous: Rect?) {
        super.onFocusChanged(gain,direction,previous); accessibility.onFocusChanged(gain,direction,previous)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = when(event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { pressed=hit(event.x,event.y); pressed!=ExploreByTouchHelper.INVALID_ID }
        MotionEvent.ACTION_UP -> {
            val id=hit(event.x,event.y)
            if(id==pressed && id!=ExploreByTouchHelper.INVALID_ID) { performClick(); onDate(first.plusDays(id.toLong())) }
            pressed=ExploreByTouchHelper.INVALID_ID; true
        }
        MotionEvent.ACTION_CANCEL -> { pressed=ExploreByTouchHelper.INVALID_ID; true }
        else -> true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
