package one.njk.sao.usage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate
import com.google.android.material.card.MaterialCardView
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.carousel.UncontainedCarouselStrategy
import com.google.android.material.textview.MaterialTextView
import one.njk.sao.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Lazy calendar cards backed by daily totals. Browsing never changes a report query. */
@SuppressLint("ViewConstructor") // Data and callbacks belong to this screen instance.
internal class UsageCalendarCarousel(context: Context,private val dashboard: UsageDashboard,private val mode: UsageCalendarMode,initial: LocalDate,
    private val onPeriod: (LocalDate)->Unit,private val onDate: (LocalDate)->Unit,
    private val yearOffset: (Int)->Int?,private val rememberYearOffset: (Int,Int)->Unit,
    private val statistics: (LinearLayout,UsageCalendarSummary)->Unit): RecyclerView(context),AutoCloseable {
    private fun dp(value: Int)=(resources.displayMetrics.density*value).toInt()
    private val monthly=mode==UsageCalendarMode.MONTH
    private val unit=if(monthly) ChronoUnit.MONTHS else ChronoUnit.YEARS
    private fun period(date: LocalDate)=if(monthly) date.withDayOfMonth(1) else date.withDayOfYear(1)
    private val latest=period(dashboard.today)
    private val recordedFirst=if(monthly) dashboard.calendarMonths.keys.minOrNull()?.atDay(1)
        else dashboard.calendarYears.keys.minOrNull()?.let { LocalDate.of(it,1,1) }
    private val first=minOf(recordedFirst ?: latest,period(initial),latest.minus(if(monthly) 11 else 2,unit))
    private val periods=unit.between(first,latest).toInt()+1
    private val snap=CarouselSnapHelper(false)
    private var itemWidth=0
    private var wanted=period(initial)
    private var placing=true
    private var closed=false
    private var focal=NO_POSITION
    private val calendarAdapter=object: Adapter<PeriodHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount()=periods
        override fun getItemId(position: Int)=first.plus(position.toLong(),unit).toEpochDay()
        override fun onCreateViewHolder(parent: ViewGroup,type: Int): PeriodHolder {
            val root=MaskableFrameLayout(parent.context).apply {
                layoutParams=LayoutParams(itemWidth,ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd=dp(8) }
                shapeAppearanceModel=shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(20).toFloat()).build()
            }
            val card=MaterialCardView(parent.context).apply {
                radius=dp(20).toFloat(); cardElevation=0f; strokeWidth=0
                setCardBackgroundColor(context.usageColor(com.google.android.material.R.attr.colorSurfaceContainer))
            }
            root.addView(card,ViewGroup.LayoutParams(-1,-2))
            val column=LinearLayout(parent.context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(12)) }
            card.addView(column)
            val stats=LinearLayout(parent.context).apply { orientation=LinearLayout.VERTICAL }
            return PeriodHolder(root,card,column,stats).also { holder ->
                root.setOnMaskChangedListener { holder.alignStatistics() }
                root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> holder.alignStatistics() }
            }
        }
        override fun onBindViewHolder(holder: PeriodHolder,position: Int) {
            val date=first.plus(position.toLong(),unit)
            val period=dashboard.selection.copy(range=mode.range,anchor=date)
            val report=(if(monthly) dashboard.calendarMonths[YearMonth.from(date)] else dashboard.calendarYears[date.year]) ?: UsageCalendarSummary.EMPTY
            holder.remember(); holder.strip=null; holder.boundYear=null; holder.restored=false
            holder.column.removeAllViews()
            holder.column.addView(MaterialTextView(context).apply {
                text=if(monthly) date.format(DateTimeFormatter.ofPattern("LLLL yyyy",resources.configuration.locales[0]))
                    else java.text.NumberFormat.getIntegerInstance(resources.configuration.locales[0]).apply { isGroupingUsed=false }.format(date.year)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium_Emphasized)
                setTextColor(context.usageColor(com.google.android.material.R.attr.colorOnSurface))
                ViewCompat.setAccessibilityHeading(this,true)
            },LinearLayout.LayoutParams(-1,-2).apply {
                marginStart=dp(8); marginEnd=dp(8); topMargin=dp(4); bottomMargin=dp(12)
            })
            val chart=UsageHeatmapView(context,period,report.dayCounts,
                dashboard.selection.anchor,dashboard.today,fixedMonthRows=monthly) { day ->
                if(position==focal && scrollState==SCROLL_STATE_IDLE) onDate(day)
            }
            if(monthly) holder.column.addView(chart,LinearLayout.LayoutParams(-1,-2))
            else {
                val strip=UsageYearScrollView(context) { position==focal && scrollState==SCROLL_STATE_IDLE }
                strip.addView(chart,ViewGroup.LayoutParams(-2,-2))
                holder.strip=strip; holder.boundYear=date.year
                holder.column.addView(strip,LinearLayout.LayoutParams(-1,-2))
                strip.post {
                    if(!closed && strip.isAttachedToWindow && holder.strip===strip && holder.bindingAdapterPosition==position) {
                        val selected=dashboard.selection.anchor.takeIf { it.year==date.year } ?: period.start
                        strip.scrollTo(yearOffset(date.year) ?: (chart.bounds(selected).centerX()-strip.width/2),0)
                        holder.restored=true
                    }
                }
            }
            holder.stats.removeAllViews()
            holder.stats.translationX=0f
            holder.column.addView(holder.stats,LinearLayout.LayoutParams(-1,-2))
            statistics(holder.stats,report)
            holder.alignStatistics()
            holder.itemView.importantForAccessibility=if(position==focal) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        override fun onViewDetachedFromWindow(holder: PeriodHolder) { holder.remember() }
        override fun onViewRecycled(holder: PeriodHolder) { holder.remember(); holder.strip=null }
    }
    private inner class PeriodHolder(val frame: MaskableFrameLayout,val card: MaterialCardView,val column: LinearLayout,val stats: LinearLayout): ViewHolder(frame) {
        var strip: UsageYearScrollView?=null
        var boundYear: Int?=null
        var restored=false
        fun remember() { if(restored) boundYear?.let { year -> strip?.let { rememberYearOffset(year,it.scrollX) } } }
        fun alignStatistics() {
            val mask=frame.maskRectF
            val left=card.left+column.left+stats.left.toFloat()
            val right=left+stats.width
            // Move only once the mask reaches the row, keeping its own tile insets.
            // Physical coordinates make the same rule work in both layout directions.
            stats.translationX=when {
                focal!=NO_POSITION && bindingAdapterPosition==focal -> 0f
                frame.width==0 || stats.width==0 || mask.isEmpty -> 0f
                frame.x+mask.centerX()<this@UsageCalendarCarousel.width/2f -> minOf(0f,mask.right-right)
                else -> maxOf(0f,mask.left-left)
            }
        }
    }
    init {
        id=if(monthly) R.id.usage_month_carousel else R.id.usage_year_carousel
        itemAnimator=null; isNestedScrollingEnabled=false
        layoutManager=CarouselLayoutManager(UncontainedCarouselStrategy()).apply { carouselAlignment=CarouselLayoutManager.ALIGNMENT_CENTER }
        adapter=calendarAdapter; snap.attachToRecyclerView(this)
        setAccessibilityDelegateCompat(object: RecyclerViewAccessibilityDelegate(this) {
            override fun onInitializeAccessibilityNodeInfo(host: View,info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
                info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                if(focal>0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,context.getString(if(monthly) R.string.usage_previous_month else R.string.usage_previous_year)))
                if(focal!=NO_POSITION && focal<periods-1) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,context.getString(if(monthly) R.string.usage_next_month else R.string.usage_next_year)))
            }
            override fun performAccessibilityAction(host: View,action: Int,args: Bundle?): Boolean {
                val direction=when(action) {
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> -1
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> 1
                    else -> return super.performAccessibilityAction(host,action,args)
                }
                if(focal==NO_POSITION || closed) return false
                val next=focal+direction
                if(next !in 0 until periods) return false
                // Navigate by period, avoiding Material's signed RTL pixel offsets.
                showPeriod(first.plus(next.toLong(),unit))
                return true
            }
        })
        addOnScrollListener(object: OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView,dx: Int,dy: Int) {
                // Material updates the mask before offsetting the child. Read its final side
                // after that offset, within the same frame, without requesting another layout.
                for(index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as PeriodHolder).alignStatistics()
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView,state: Int) {
                if(state!=SCROLL_STATE_IDLE) setFocal(NO_POSITION)
                if(state==SCROLL_STATE_IDLE) post { settle() }
            }
        })
        scrollToPosition(position(period(initial)))
        doOnPreDraw { placing=false; settle() }
    }
    override fun onMeasure(widthSpec: Int,heightSpec: Int) {
        val available=MeasureSpec.getSize(widthSpec)
        val nextWidth=available-dp(if(available>=dp(300)) 48 else 8)
        if(itemWidth!=nextWidth) {
            itemWidth=nextWidth
            for(index in 0 until childCount) getChildAt(index).layoutParams.width=itemWidth
        }
        super.onMeasure(widthSpec,heightSpec)
    }
    private fun position(date: LocalDate)=unit.between(first,date).toInt().coerceIn(0,periods-1)
    fun showPeriod(date: LocalDate) {
        val next=period(date).coerceIn(first,latest)
        if(wanted==next || closed) return
        wanted=next
        smoothScrollToPosition(position(next))
    }
    fun rememberStrips() {
        for(index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as PeriodHolder).remember()
    }
    private fun setFocal(position: Int) {
        focal=position
        for(index in 0 until childCount) getChildAt(index).let { child ->
            child.importantForAccessibility=if(getChildAdapterPosition(child)==position) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (getChildViewHolder(child) as PeriodHolder).alignStatistics()
        }
    }
    private fun settle() {
        if(closed || placing || !isAttachedToWindow || scrollState!=SCROLL_STATE_IDLE || isComputingLayout) return
        val manager=layoutManager ?: return
        val view=snap.findSnapView(manager) ?: return
        if(snap.calculateDistanceToFinalSnap(manager,view)?.any { kotlin.math.abs(it)>1 }==true) return
        val position=getChildAdapterPosition(view)
        if(position==NO_POSITION) return
        setFocal(position)
        wanted=first.plus(position.toLong(),unit); onPeriod(wanted)
    }
    override fun close() { if(closed) return; rememberStrips(); closed=true; stopScroll(); snap.attachToRecyclerView(null); adapter=null }
}
