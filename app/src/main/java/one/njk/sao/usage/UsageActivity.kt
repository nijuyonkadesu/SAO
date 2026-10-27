package one.njk.sao.usage

import dagger.hilt.android.AndroidEntryPoint
import one.njk.sao.R
import one.njk.sao.ui.ScreenActivity

@AndroidEntryPoint
class UsageActivity: ScreenActivity() {
    override val screen=UsageFragment::class.java
    override val titleResource=R.string.usage_title
}
