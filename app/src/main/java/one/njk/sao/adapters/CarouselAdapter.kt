package one.njk.sao.adapters

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.njk.sao.BuildConfig
import one.njk.sao.CarouselFragment
import one.njk.sao.databinding.CarouselViewWaifuBinding
import one.njk.sao.models.Waifu
import one.njk.sao.operatingMode
import java.io.File
import java.io.OutputStream
import kotlin.math.max

class CarouselAdapter(
    val imageLoader: ImageLoader,
    val context: Context,
    val lifecycleScope: CoroutineScope,
    val inHousePagination: (Int) -> Unit,
    val bookmarkMe: (String) -> Unit
): ListAdapter<Waifu, CarouselAdapter.WaifuViewHolder>(DiffCallback) {

    // List Adapter makes uses of this to choose whether to update an item in a list
    companion object DiffCallback : DiffUtil.ItemCallback<Waifu>() {
        var maxLoadedItem = 0
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
        val lifecycleScope: CoroutineScope,
        val bookmarkMe: (String) -> Unit,
    ):
        RecyclerView.ViewHolder(binding.root) {
            @OptIn(ExperimentalCoilApi::class)
            fun bind(waifu: Waifu){
                binding.apply {
                    this.waifu = waifu
                    this.imageLoader = imageLoaderHilt
                    executePendingBindings()
                }
                binding.carouselItemContainer.setOnClickListener {
                    if(operatingMode != CarouselFragment.OperatingMode.IGNORE)
                        lifecycleScope.launch {
                            if(BuildConfig.DEBUG)
                                Log.d("url", waifu.url)

                            imageLoaderHilt.diskCache!!.openSnapshot(waifu.url)?.let {
                                val file = it.data.toFile()
                                if(BuildConfig.DEBUG)
                                    Log.d("url", file.length().toString())
                                it.close()

                                when (operatingMode) {
                                    CarouselFragment.OperatingMode.SHARE -> {
                                        val uri = uriFromCacheDir(file, waifu.url)
                                        launchShareIntent(uri)
                                    }
                                    CarouselFragment.OperatingMode.BOOKMARK -> {
                                        Log.d("fav", "bookmarked: ${waifu.url}")
                                        bookmarkMe(waifu.url)
                                    }
                                    else -> {
                                        saveToPicturesDir(file, waifu.url)
                                        Toast.makeText(context, "Downloaded", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                }
            }
        fun launchShareIntent(uri: Uri){
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
        }

        fun uriFromCacheDir(file: File, url: String): Uri{
            val filename = getFilename(url)
            val ext = getFileExtension(url)
            val shareDir = File(context.cacheDir, "share_cache")

            val path = file.copyTo(
                File(shareDir,
                    "$filename.$ext"),
                true)

            return FileProvider.getUriForFile(context, "one.njk.sao.fileprovider",path)
        }

        fun saveToPicturesDir(file: File, url: String) {
            val filename = getFilename(url)
            val ext = getFileExtension(url)

            var imageUri: Uri?
            var fos: OutputStream?

            val contentValues = ContentValues().apply {
                val mimeType = when (ext) {
                    "jpeg" -> "image/jpeg"
                    "jpg" -> "image/jpeg"
                    "png" -> "image/png"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }

                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            context.contentResolver.also { resolver ->
                imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { resolver.openOutputStream(it) }
            }

            fos?.write(file.readBytes())

            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(imageUri!!, contentValues, null, null)
        }
        fun getFilename(url: String): String {
            val endpoint = url.split('.')
            return endpoint[endpoint.lastIndex - 1].split('/').last()
        }

        fun getFileExtension(url: String): String {
            return url.split('.').last()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaifuViewHolder {
        return WaifuViewHolder(
            CarouselViewWaifuBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            imageLoader,
            context,
            lifecycleScope,
            bookmarkMe,
        )
    }

    override fun onBindViewHolder(holder: WaifuViewHolder, position: Int) {
        val waifu = getItem(position)
        holder.bind(waifu)

        // Reset max on changing category
        if(position == 0) maxLoadedItem = 0

        maxLoadedItem = max(position, maxLoadedItem)
        inHousePagination(maxLoadedItem)

        if(BuildConfig.DEBUG)
            Log.d("url", "Max Loaded position: $position")
    }
}
