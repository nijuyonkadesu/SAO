package one.njk.sao.adapters

import android.widget.ImageView
import androidx.core.net.toUri
import androidx.databinding.BindingAdapter
import coil.ImageLoader
import coil.request.ImageRequest
import one.njk.sao.R

@BindingAdapter("imageUrl", "imageLoader")
fun bindImage(imgView: ImageView, imgUrl: String?, imageLoader: ImageLoader) {

    if(!imgUrl.isNullOrEmpty()){
        val imgUri = imgUrl.toUri().buildUpon().scheme("https").build()
        // pass a request to imageloader
        // https://t.me/addstickers/LINE_White_Cat_Girl - awesome Sticker Pack By LINE!
        imageLoader.enqueue(
            ImageRequest.Builder(imgView.context)
                .data(imgUri)
                .target(imgView)
                .placeholder(R.drawable.eager)
                .build()
        )
    }
}
