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
import one.njk.sao.ui.LayoutAwareCarouselSnapHelper
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.carousel.UncontainedCarouselStrategy
import com.google.android.material.textview.MaterialTextView
import one.njk.sao.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Lazy month cards backed by the existing daily totals. Browsing never changes a report query. */
@SuppressLint("ViewConstructor") // Data and callbacks belong to this screen instance.
internal class UsageMonthCarousel(context: Context,private val dashboard: UsageDashboard,initial: YearMonth,
    private val onMonth: (YearMonth)->Unit,private val onDate: (LocalDate)->Unit,
    private val statistics: (LinearLayout,UsagePeriodSummary)->Unit): RecyclerView(context),AutoCloseable {
    private fun dp(value: Int)=(resources.displayMetrics.density*value).toInt()
    private val latest=YearMonth.from(dashboard.today)
    private val firstMonth=minOf(dashboard.calendarMonths.keys.minOrNull() ?: latest,
        initial,latest.minusMonths(11))
    private val months=ChronoUnit.MONTHS.between(firstMonth,latest).toInt()+1
    private val snap=LayoutAwareCarouselSnapHelper(this)
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
            val stats=LinearLayout(parent.context).apply { orientation=LinearLayout.VERTICAL }
            return MonthHolder(root,card,column,stats).also { holder ->
                root.setOnMaskChangedListener { holder.alignStatistics() }
                root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> holder.alignStatistics() }
            }
        }
        override fun onBindViewHolder(holder: MonthHolder,position: Int) {
            val month=firstMonth.plusMonths(position.toLong())
            val period=dashboard.selection.copy(range=UsageRange.MONTH,anchor=month.atDay(1))
            val report=dashboard.calendarMonths[month] ?: UsagePeriodSummary.EMPTY
            holder.column.removeAllViews()
            holder.column.addView(MaterialTextView(context).apply {
                text=month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy",resources.configuration.locales[0]))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium_Emphasized)
                setTextColor(context.usageColor(com.google.android.material.R.attr.colorOnSurface))
                ViewCompat.setAccessibilityHeading(this,true)
            },LinearLayout.LayoutParams(-1,-2).apply {
                marginStart=dp(8); marginEnd=dp(8); topMargin=dp(4); bottomMargin=dp(12)
            })
            holder.column.addView(UsageHeatmapView(context,period,report.dayCounts,
                dashboard.selection.anchor,dashboard.today,fixedMonthRows=true,intensity=report.intensity) { date ->
                if(position==focal && scrollState==SCROLL_STATE_IDLE) onDate(date)
            },LinearLayout.LayoutParams(-1,-2))
            holder.stats.removeAllViews()
            holder.stats.translationX=0f
            holder.column.addView(holder.stats,LinearLayout.LayoutParams(-1,-2))
            statistics(holder.stats,report)
            holder.alignStatistics()
            holder.itemView.importantForAccessibility=if(position==focal) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
    private inner class MonthHolder(val frame: MaskableFrameLayout,val card: MaterialCardView,val column: LinearLayout,val stats: LinearLayout): ViewHolder(frame) {
        fun alignStatistics() {
            val mask=frame.maskRectF
            val left=card.left+column.left+stats.left.toFloat()
            val right=left+stats.width
            // Move only once the mask reaches the row, keeping its own tile insets.
            // Physical coordinates make the same rule work in both layout directions.
            stats.translationX=when {
                focal!=NO_POSITION && bindingAdapterPosition==focal -> 0f
                frame.width==0 || stats.width==0 || mask.isEmpty -> 0f
                frame.x+mask.centerX()<this@UsageMonthCarousel.width/2f -> minOf(0f,mask.right-right)
                else -> maxOf(0f,mask.left-left)
            }
        }
    }
    init {
        id=R.id.usage_month_carousel
        itemAnimator=null; isNestedScrollingEnabled=false
        layoutManager=CarouselLayoutManager(UncontainedCarouselStrategy()).apply { carouselAlignment=CarouselLayoutManager.ALIGNMENT_CENTER }
        adapter=monthAdapter; snap.attachToRecyclerView(this)
        addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> post { settle() } }
        setAccessibilityDelegateCompat(object: RecyclerViewAccessibilityDelegate(this) {
            override fun onInitializeAccessibilityNodeInfo(host: View,info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host,info)
                info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
                info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
                if(focal>0) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,context.getString(R.string.usage_previous_month)))
                if(focal!=NO_POSITION && focal<months-1) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,context.getString(R.string.usage_next_month)))
            }
            override fun performAccessibilityAction(host: View,action: Int,args: Bundle?): Boolean {
                val direction=when(action) {
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> -1
                    AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> 1
                    else -> return super.performAccessibilityAction(host,action,args)
                }
                if(focal==NO_POSITION || closed) return false
                val next=focal+direction
                if(next !in 0 until months) return false
                // Navigate by month, avoiding Material's signed RTL pixel offsets.
                showMonth(firstMonth.plusMonths(next.toLong()))
                return true
            }
        })
        addOnScrollListener(object: OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView,dx: Int,dy: Int) {
                // Material updates the mask before offsetting the child. Read its final side
                // after that offset, within the same frame, without requesting another layout.
                for(index in 0 until childCount) (getChildViewHolder(getChildAt(index)) as MonthHolder).alignStatistics()
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView,state: Int) {
                if(state!=SCROLL_STATE_IDLE) setFocal(NO_POSITION)
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
            (getChildViewHolder(child) as MonthHolder).alignStatistics()
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
