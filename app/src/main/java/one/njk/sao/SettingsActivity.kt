package one.njk.sao

import dagger.hilt.android.AndroidEntryPoint
import one.njk.sao.ui.ScreenActivity

@AndroidEntryPoint
class SettingsActivity: ScreenActivity() {
    override val screen=SettingsFragment::class.java
    override val titleResource=R.string.action_settings
}
