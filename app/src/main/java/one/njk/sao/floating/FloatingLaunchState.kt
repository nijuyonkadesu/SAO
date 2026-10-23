package one.njk.sao.floating

import androidx.lifecycle.ViewModel

/** A configuration change continues the same visit, including an explicit Stop. */
class FloatingLaunchState : ViewModel() {
    var skipNextStart = false
}
