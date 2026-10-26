package one.njk.sao.usage

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import one.njk.sao.R
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Weekday rows and week columns, with real focusable date controls rather than tiny canvas targets. */
@SuppressLint("ViewConstructor") // Requires the selected month and a date callback.
internal class UsageMonthView(context: Context,month: YearMonth,days: Map<Long,Long>,today: LocalDate,
    firstWeekday: DayOfWeek,onDate: (LocalDate)->Unit): HorizontalScrollView(context) {
    private lateinit var grid: LinearLayout
    private var gridMinimum=0
    private var viewportWidth=0
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    init {
        isFillViewport=true; isHorizontalScrollBarEnabled=false
        val locale=resources.configuration.locales[0]
        val number=NumberFormat.getIntegerInstance(locale)
        val first=month.atDay(1)
        val offset=(first.dayOfWeek.value-firstWeekday.value+7)%7
        val columns=(offset+month.lengthOfMonth()+6)/7
        gridMinimum=dp(32+columns*52)
        grid=object: LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                // HorizontalScrollView gives its child UNSPECIFIED width, even for a fixed LayoutParams width.
                super.onMeasure(MeasureSpec.makeMeasureSpec(maxOf(gridMinimum,viewportWidth),MeasureSpec.EXACTLY),heightMeasureSpec)
            }
        }.apply { orientation=LinearLayout.VERTICAL; minimumWidth=gridMinimum }
        addView(grid,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        for(weekday in 0..6) {
            val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            grid.addView(row,LinearLayout.LayoutParams(-1,dp(52)))
            row.addView(MaterialTextView(context).apply {
                text=firstWeekday.plus(weekday.toLong()).getDisplayName(TextStyle.NARROW,locale)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(context.usageColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                gravity=Gravity.CENTER
                importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(32),-1))
            for(week in 0 until columns) {
                val day=week*7+weekday-offset+1
                val button=MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    minimumWidth=dp(48); minimumHeight=dp(48)
                    insetTop=0; insetBottom=0; cornerRadius=dp(12); strokeWidth=0
                    setPadding(0,0,0,0)
                }
                row.addView(button,LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart=dp(2); marginEnd=dp(2) })
                if(day !in 1..month.lengthOfMonth()) { button.visibility=INVISIBLE; continue }
                val date=month.atDay(day)
                val count=days[date.toEpochDay()] ?: 0L
                val isToday=date==today
                val background=when { isToday -> com.google.android.material.R.attr.colorPrimaryContainer; count>0 -> androidx.appcompat.R.attr.colorPrimary; else -> com.google.android.material.R.attr.colorSurfaceContainerHighest }
                val foreground=when { isToday -> com.google.android.material.R.attr.colorOnPrimaryContainer; count>0 -> com.google.android.material.R.attr.colorOnPrimary; else -> com.google.android.material.R.attr.colorOnSurfaceVariant }
                button.text=number.format(day)
                button.backgroundTintList=ColorStateList.valueOf(context.usageColor(background))
                button.setTextColor(context.usageColor(foreground))
                button.contentDescription=context.getString(R.string.usage_date_activity,date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)),context.actionCount(count))
                button.isEnabled=date<=today
                if(isToday) { button.strokeWidth=dp(1); button.strokeColor=ColorStateList.valueOf(context.usageColor(androidx.appcompat.R.attr.colorPrimary)) }
                button.setOnClickListener { onDate(date) }
            }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        // HorizontalScrollView otherwise measures weighted rows with an unbounded width.
        viewportWidth=MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
