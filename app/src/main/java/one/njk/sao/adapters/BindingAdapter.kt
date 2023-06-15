package one.njk.sao.adapters

import android.widget.ImageView
import androidx.core.net.toUri
import androidx.databinding.BindingAdapter
import coil.imageLoader
import coil.request.ImageRequest

@BindingAdapter("imageUrl")
fun bindImage(imgView: ImageView, imgUrl: String?) {

    // Custom ImageLoader with GIF support obtained from factory class
    val imageLoader = imgView.context.imageLoader

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