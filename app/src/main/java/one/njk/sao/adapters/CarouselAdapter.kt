package one.njk.sao.adapters

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.njk.sao.databinding.CarouselViewWaifuBinding
import one.njk.sao.models.Waifu

class CarouselAdapter(
    val imageLoader: ImageLoader,
    val context: Context,
    val lifecycleScope: CoroutineScope
): ListAdapter<Waifu, CarouselAdapter.WaifuViewHolder>(DiffCallback) {

    // List Adapter makes uses of this to choose whether to update an item in a list
    companion object DiffCallback : DiffUtil.ItemCallback<Waifu>() {
        override fun areItemsTheSame(oldItem: Waifu, newItem: Waifu): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: Waifu, newItem: Waifu): Boolean {
            return oldItem.url == newItem.url
        }
    }

    class WaifuViewHolder(
        private val binding: CarouselViewWaifuBinding,
        val imageLoaderHilt: ImageLoader,
        val context: Context,
        val lifecycleScope: CoroutineScope
    ):
        RecyclerView.ViewHolder(binding.root) {
            fun bind(waifu: Waifu){
                binding.apply {
//                    lifecycleOwner = viewLifecycleOwner
                    this.waifu = waifu
                    this.imageLoader = imageLoaderHilt
                    executePendingBindings()
                }
                binding.carouselItemContainer.setOnClickListener {
                    lifecycleScope.launch {
                        Log.d("url", waifu.url)
                        val request = ImageRequest.Builder(context)
                            .data(waifu.url)
                            .build()
                        val drawable = imageLoaderHilt.execute(request).drawable
                        val bitmap = (drawable as? BitmapDrawable)?.bitmap

                        val path = MediaStore.Images.Media.insertImage(
                            context.contentResolver,
                            bitmap,
                            "SAO:",
                            null
                        )

                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/*"
                            val uri = Uri.parse(path)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TEXT, "sus")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
                    }
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaifuViewHolder {
        return WaifuViewHolder(
            CarouselViewWaifuBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            imageLoader,
            context,
            lifecycleScope
        )
    }

    override fun onBindViewHolder(holder: WaifuViewHolder, position: Int) {
        val waifu = getItem(position)
        holder.bind(waifu)
    }
}
