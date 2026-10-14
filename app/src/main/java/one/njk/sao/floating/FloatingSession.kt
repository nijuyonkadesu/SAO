package one.njk.sao.floating

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process state describes the running service; preferences never pretend it is running. */
object FloatingSession {
    private val state = MutableStateFlow(false)
    val active = state.asStateFlow()
    enum class IconState { STOPPED, VISIBLE, TUCKED, HIDDEN, PICKER }
    private val icon = MutableStateFlow(IconState.STOPPED)
    val iconState = icon.asStateFlow()
    internal fun icon(value: IconState) { icon.value = value }
    data class ControlState(val active: Boolean, val generation: Long)
    private var generation = android.os.SystemClock.elapsedRealtimeNanos()
    @Synchronized fun controlState() = ControlState(state.value, generation)
    @Synchronized internal fun active(value: Boolean) {
        if (state.value != value) generation = android.os.SystemClock.elapsedRealtimeNanos()
        state.value = value; if (!value) icon.value = IconState.STOPPED
    }
    private val pickerOwners = mutableSetOf<Any>()
    private val picker = MutableStateFlow(false)
    internal val pickerVisibility = picker.asStateFlow()
    val pickerVisible get() = picker.value

    /** Opaque ViewModel-owned tokens survive recreation without retaining an Activity. */
    @Synchronized internal fun pickerVisible(owner: Any, visible: Boolean) {
        if (visible) pickerOwners.add(owner) else pickerOwners.remove(owner)
        picker.value = pickerOwners.isNotEmpty()
    }
}
