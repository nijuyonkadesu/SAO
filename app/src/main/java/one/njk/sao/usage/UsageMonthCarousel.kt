package one.njk.sao.usage

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.carousel.UncontainedCarouselStrategy
import one.njk.sao.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Lazy month cards backed by the existing daily totals. Browsing never changes a report query. */
@SuppressLint("ViewConstructor") // Data and callbacks belong to this screen instance.
internal class UsageMonthCarousel(context: Context,private val dashboard: UsageDashboard,initial: YearMonth,
    private val onMonth: (YearMonth)->Unit,private val onDate: (LocalDate)->Unit,
    private val statistics: (LinearLayout,UsageMonthSummary)->Unit): RecyclerView(context),AutoCloseable {
    private fun dp(value: Int)=(resources.displayMetrics.density*value).toInt()
    private val latest=YearMonth.from(dashboard.today)
    val firstMonth=minOf(dashboard.calendarMonths.keys.minOrNull() ?: latest,
        initial,latest.minusMonths(11))
    private val months=ChronoUnit.MONTHS.between(firstMonth,latest).toInt()+1
    private val snap=CarouselSnapHelper(false)
    private var itemWidth=0
    private var wanted=initial
    private var placing=true
    private var closed=false
    private var focal=NO_POSITION
    private val monthAdapter=object: Adapter<MonthHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount()=months
        override fun getItemId(position: Int)=firstMonth.plusMonths(position.toLong()).let { it.year*12L+it.monthValue }
        override fun onCreateViewHolder(parent: ViewGroup,type: Int): MonthHolder {
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
            return MonthHolder(root,column)
        }
        override fun onBindViewHolder(holder: MonthHolder,position: Int) {
            val month=firstMonth.plusMonths(position.toLong())
            val period=dashboard.selection.copy(range=UsageRange.MONTH,anchor=month.atDay(1))
            val report=dashboard.calendarMonths[month] ?: UsageMonthSummary.EMPTY
            holder.column.removeAllViews()
            holder.column.addView(UsageHeatmapView(context,period,report.dayCounts,
                dashboard.selection.anchor,dashboard.today,fixedMonthRows=true) { date ->
                if(position==focal && scrollState==SCROLL_STATE_IDLE) onDate(date)
            },LinearLayout.LayoutParams(-1,-2))
            statistics(holder.column,report)
            holder.itemView.importantForAccessibility=if(position==focal) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
    private class MonthHolder(root: View,val column: LinearLayout): ViewHolder(root)
    init {
        id=R.id.usage_month_carousel
        itemAnimator=null; isNestedScrollingEnabled=false
        layoutManager=CarouselLayoutManager(UncontainedCarouselStrategy()).apply { carouselAlignment=CarouselLayoutManager.ALIGNMENT_CENTER }
        adapter=monthAdapter; snap.attachToRecyclerView(this)
        addOnScrollListener(object: OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView,state: Int) {
                if(state==SCROLL_STATE_DRAGGING) setFocal(NO_POSITION)
                if(state==SCROLL_STATE_IDLE) post { settle() }
            }
        })
        scrollToPosition(position(initial))
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
    private fun position(month: YearMonth)=ChronoUnit.MONTHS.between(firstMonth,month).toInt().coerceIn(0,months-1)
    fun showMonth(month: YearMonth) {
        if(wanted==month || closed) return
        wanted=month
        smoothScrollToPosition(position(month))
    }
    private fun setFocal(position: Int) {
        focal=position
        for(index in 0 until childCount) getChildAt(index).let { child ->
            child.importantForAccessibility=if(getChildAdapterPosition(child)==position) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
        wanted=firstMonth.plusMonths(position.toLong()); onMonth(wanted)
    }
    override fun close() { closed=true; stopScroll(); snap.attachToRecyclerView(null); adapter=null }
}
