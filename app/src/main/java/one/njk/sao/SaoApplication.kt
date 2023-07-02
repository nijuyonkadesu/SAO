package one.njk.sao

import android.app.Application
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp

@Volatile
lateinit var operatingMode: CarouselFragment.OperatingMode
@HiltAndroidApp
class SaoApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

}
