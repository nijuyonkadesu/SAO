package one.njk.sao.waifu

import android.widget.ImageView
import androidx.core.net.toUri
import androidx.databinding.BindingAdapter
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest

@BindingAdapter("imageUrl")
fun bindImage(imgView: ImageView, imgUrl: String?) {
    // Custom ImageLoader with GIF support
    val imageLoader = ImageLoader.Builder(imgView.context)
        .components {
            add(ImageDecoderDecoder.Factory())
        }.build()

    if(!imgUrl.isNullOrEmpty()){
        val imgUri = imgUrl.toUri().buildUpon().scheme("https").build()
        // pass a request to imageloader
        imageLoader.enqueue(
            ImageRequest.Builder(imgView.context)
                .data(imgUri)
                .target(imgView)
                .build()
        )
        // TODO: Have placeholders and optional transformers
    }
}

// TODO: Error || Loading animation