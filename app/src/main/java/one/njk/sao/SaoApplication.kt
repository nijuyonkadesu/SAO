package one.njk.sao

import android.app.Application
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp

@Volatile
var operatingMode = CarouselFragment.OperatingMode.IGNORE
@HiltAndroidApp
class SaoApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

}
