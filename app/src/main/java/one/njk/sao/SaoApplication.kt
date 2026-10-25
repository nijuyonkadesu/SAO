package one.njk.sao

import android.app.Application
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SaoApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguages.initialize(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguages.refresh(this)
    }

}
