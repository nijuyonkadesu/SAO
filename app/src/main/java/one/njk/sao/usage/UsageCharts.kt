package one.njk.sao.usage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import androidx.core.graphics.ColorUtils
import one.njk.sao.R
import java.text.NumberFormat
import kotlin.math.max

internal fun Context.actionCount(count: Long) = resources.getQuantityString(R.plurals.usage_actions_count,count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(count))

internal fun Context.usageSp(value: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,value,resources.displayMetrics)

internal data class UsagePoint(val label: String,val value: Long)
internal fun Context.usageColor(attribute: Int) = MaterialColors.getColor(this,attribute,javaClass.simpleName)
internal fun Context.usageSeriesColors() = listOf(androidx.appcompat.R.attr.colorPrimary,
    com.google.android.material.R.attr.colorSecondary,com.google.android.material.R.attr.colorTertiary,
    com.google.android.material.R.attr.colorOutline).map(::usageColor)

/** Small native charts, with the same data also rendered as accessible text rows. */
@SuppressLint("ViewConstructor") // Programmatic report view, never inflated from XML.
internal class UsageTrendView(context: Context,private val points: List<UsagePoint>,private val line: Boolean = false,
    private val onPoint: (Int)->Unit = {}): View(context) {
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val plot=RectF()
    private val path=Path()
    private val area=Path()
    private val number=NumberFormat.getIntegerInstance(resources.configuration.locales[0])
    private val dataMaximum=(points.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1)
    private val ticks=dataMaximum.coerceAtMost(3).toInt()
    private val maximum=((dataMaximum+ticks-1)/ticks)*ticks
    private val primary=context.usageColor(androidx.appcompat.R.attr.colorPrimary)
    private val secondary=context.usageColor(com.google.android.material.R.attr.colorTertiary)
    private val outline=context.usageColor(com.google.android.material.R.attr.colorOutlineVariant)
    private val foreground=context.usageColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val labels=(0..ticks).map { number.format(maximum*it/ticks) }
    private var chosen=-1
    private var labelStride=1
    private var denseHourlyLabels=false
    init {
        isFocusable=true; isClickable=true
        contentDescription=context.getString(R.string.usage_chart_summary,
            context.getString(if(line) R.string.usage_daily_trend else R.string.usage_hourly),
            points.joinToString("; ") { context.getString(R.string.usage_point_value,it.label,context.actionCount(it.value)) })
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        paint.textSize=context.usageSp(12f)
        val left=labels.maxOf { paint.measureText(it) }+12*density
        plot.set(left,maxOf(12*density,paint.textSize),(w-12*density).coerceAtLeast(left+1),h-paint.textSize-24*density)
        val widest=points.maxOfOrNull { paint.measureText(it.label) }?.plus(8*density) ?: 1f
        labelStride=kotlin.math.ceil(widest*points.size/plot.width().coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        denseHourlyLabels=widest*5<plot.width()
    }
    private fun x(index: Int): Float {
        val indexInDirection=if(layoutDirection==LAYOUT_DIRECTION_RTL) points.lastIndex-index else index
        return plot.left+plot.width()*(indexInDirection+0.5f)/points.size.coerceAtLeast(1)
    }
    private fun y(value: Long)=plot.bottom-plot.height()*(value.toDouble()/maximum).toFloat()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.textSize=context.usageSp(12f)
        paint.strokeWidth=density; paint.style=Paint.Style.FILL
        for(index in 0..ticks) {
            val y=plot.bottom-plot.height()*index/ticks
            paint.color=ColorUtils.setAlphaComponent(outline,128); canvas.drawLine(plot.left,y,plot.right,y,paint)
            paint.color=foreground; paint.textAlign=Paint.Align.RIGHT
            canvas.drawText(labels[index],plot.left-8*density,y+paint.textSize/3,paint)
        }
        if(points.isEmpty()) return
        val barWidth=(plot.width()/points.size*0.55f).coerceAtMost(32*density)
        if(line) {
            path.reset()
            points.forEachIndexed { index,point -> if(index==0) path.moveTo(x(index),y(point.value)) else path.lineTo(x(index),y(point.value)) }
            area.set(path); area.lineTo(x(points.lastIndex),plot.bottom); area.lineTo(x(0),plot.bottom); area.close()
            paint.color=ColorUtils.setAlphaComponent(primary,22); canvas.drawPath(area,paint)
            paint.color=primary; paint.style=Paint.Style.STROKE; paint.strokeWidth=3*density
            canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
        }
        points.forEachIndexed { index,point ->
            paint.color=if(index==chosen) secondary else primary
            if(line) canvas.drawCircle(x(index),y(point.value),4*density,paint)
            else if(point.value>0) canvas.drawRoundRect(x(index)-barWidth/2,y(point.value),x(index)+barWidth/2,plot.bottom,barWidth/2,barWidth/2,paint)
            val show=if(points.size<=7) index%labelStride==0 else if(denseHourlyLabels) index==0 || index==6 || index==12 || index==18 || index==points.lastIndex else index==0 || index==12 || index==points.lastIndex
            if(show) {
                paint.color=foreground; paint.textAlign=Paint.Align.CENTER
                val half=paint.measureText(point.label)/2
                val labelX=x(index).coerceIn(half,width-half)
                canvas.drawText(point.label,labelX,height-6*density,paint)
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.action==MotionEvent.ACTION_UP && points.isNotEmpty()) {
            chosen=points.indices.minBy { kotlin.math.abs(x(it)-event.x) }; invalidate(); performClick(); return true
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean {
        super.performClick()
        if(points.isNotEmpty()) {
            chosen=chosen.coerceIn(0,points.lastIndex)
            val point=points[chosen]
            announceForAccessibility(context.getString(R.string.usage_point_value,point.label,context.actionCount(point.value)))
            onPoint(chosen)
        }
        return true
    }
}

@SuppressLint("ViewConstructor") // Programmatic report view, never inflated from XML.
internal class UsageDonutView(context: Context,private val points: List<UsagePoint>): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val emphasisTypeface=Typeface.create(Typeface.DEFAULT,600,false)
    private val bounds=RectF()
    private val colors=context.usageSeriesColors()
    private val sum=points.sumOf { it.value }
    private val total=NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(sum)
    init {
        isFocusable=true
        contentDescription=context.getString(R.string.usage_chart_summary,context.getString(R.string.usage_destinations),
            points.joinToString("; ") { context.getString(R.string.usage_point_value,it.label,context.actionCount(it.value)) })
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke=20*resources.displayMetrics.density
        val size=minOf(width,height).toFloat()
        bounds.set((width-size)/2+stroke,(height-size)/2+stroke,(width+size)/2-stroke,(height+size)/2-stroke)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=stroke; paint.strokeCap=Paint.Cap.ROUND
        paint.color=context.usageColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
        canvas.drawOval(bounds,paint)
        var angle=-90f
        if(sum>0) points.forEachIndexed { index,point ->
            val sweep=(point.value.toDouble()/sum*360).toFloat()
            paint.color=colors[index%colors.size]
            canvas.drawArc(bounds,angle+2,max(0f,sweep-4),false,paint); angle+=sweep
        }
        paint.style=Paint.Style.FILL; paint.strokeCap=Paint.Cap.BUTT
        paint.color=context.usageColor(com.google.android.material.R.attr.colorOnSurface)
        paint.textAlign=Paint.Align.CENTER; paint.typeface=emphasisTypeface
        paint.textSize=context.usageSp(32f)
        val fit=(bounds.width()-stroke-12*resources.displayMetrics.density)/paint.measureText(total).coerceAtLeast(1f)
        if(fit<1) paint.textSize*=fit
        canvas.drawText(total,width/2f,height/2f,paint)
        paint.typeface=Typeface.DEFAULT; paint.textSize=context.usageSp(12f)
        paint.color=context.usageColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        canvas.drawText(context.getString(R.string.usage_total),width/2f,height/2f+paint.textSize*1.7f,paint)
    }
}
