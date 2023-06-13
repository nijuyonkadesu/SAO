package one.njk.sao.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import one.njk.sao.databinding.CarouselViewWaifuBinding
import one.njk.sao.models.Waifu

class CarouselAdapter: ListAdapter<Waifu, CarouselAdapter.WaifuViewHolder>(DiffCallback) {

    // List Adapter makes uses of this to choose whether to update an item in a list
    companion object DiffCallback : DiffUtil.ItemCallback<Waifu>() {
        override fun areItemsTheSame(oldItem: Waifu, newItem: Waifu): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: Waifu, newItem: Waifu): Boolean {
            return oldItem.url == newItem.url
        }
    }

    class WaifuViewHolder(private val binding: CarouselViewWaifuBinding):
        RecyclerView.ViewHolder(binding.root) {
            fun bind(waifu: Waifu){
                binding.apply {
                    this.waifu = waifu
                    executePendingBindings()
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaifuViewHolder {
        return WaifuViewHolder(
            CarouselViewWaifuBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: WaifuViewHolder, position: Int) {
        val waifu = getItem(position)
        holder.bind(waifu)
    }
}