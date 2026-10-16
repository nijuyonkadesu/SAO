package one.njk.sao.floating

import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread, lifecycle-owned reading pause for each changing dock label. */
internal class PickerMarquees(private val scope: CoroutineScope, vararg views: TextView) {
    private class Label(val view: TextView) {
        var pending: Job? = null
        fun reset() {
            pending?.cancel(); pending = null
            view.isSelected = false
        }
    }
    private val labels = views.map(::Label)
    private var enabled = false

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        refresh()
    }

    fun text(view: TextView, value: String) {
        if (view.text.toString() == value) return
        labels.first { it.view === view }.reset()
        view.text = value
    }

    fun refresh() {
        for (label in labels) {
            if (!enabled || !label.view.isShown || label.view.text.isEmpty()) label.reset()
            else if (label.pending == null) label.pending = scope.launch {
                delay(1_500)
                if (enabled && label.view.isShown) label.view.isSelected = true
            }
        }
    }
}
