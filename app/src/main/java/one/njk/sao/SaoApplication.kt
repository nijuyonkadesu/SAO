package one.njk.sao

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SaoApplication: Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    // customizing factory class to add GIF Decoder, context.ImageLoader is a singleton
    // we obtain from this factory class [see BindingAdapter.kt]
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(applicationContext)
            .components {
                add(ImageDecoderDecoder.Factory())
            }.build()
    }
}