package one.njk.sao

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import one.njk.sao.adapters.CarouselAdapter
import one.njk.sao.databinding.FragmentCarouselBinding
import one.njk.sao.models.Waifu
import one.njk.sao.viewmodels.ArtsViewModel

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class CarouselFragment : Fragment(), MenuProvider {

    private var _binding: FragmentCarouselBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private val viewModel: ArtsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        // Menu Provider method: https://stackoverflow.com/questions/71917856/sethasoptionsmenuboolean-unit-is-deprecated-deprecated-in-java
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider( this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentCarouselBinding.inflate(inflater, container, false)

        val adapter = CarouselAdapter()
        _binding!!.apply {
            bottomAppBar.setOnMenuItemClickListener {
                when(it.itemId){
                    R.id.bookmark -> {
                        // TODO: Replace with viewmodel bookmark to fav list
                        Toast.makeText(context, "bookmark", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.download -> {
                        Toast.makeText(context, "download", Toast.LENGTH_SHORT).show()
                        // TODO: Replace with viewmodel download with URI intent
                        true
                    }
                    else -> false
                }
            }
            share.setOnClickListener {
                // Get the screen size
                val displayMetrics = resources.displayMetrics
                val x = displayMetrics.widthPixels / 2.3f
                val y = displayMetrics.heightPixels / 3f

                Log.d("screen", "${displayMetrics.widthPixels} -> $x")
                // TODO: This pixel calculation might break in other DPI setting than default

                // Find the child view based on x and y pixel values
                val focusedChild = carouselRecyclerView.findChildViewUnder(x, y)
                focusedChild?.performClick()
            }
            carouselRecyclerView.adapter = adapter
            carouselRecyclerView.addOnScrollListener(
                OnScrollListener(lifecycleScope, adapter, viewModel.waifuList)
            )
            addCategoryChips(categories, viewModel.sfwCategories)
        }
        lifecycleScope.launch {
            subscribeUi(adapter)
        }
        return binding.root

    }
    private fun addCategoryChips(chipGroup: ChipGroup, categories: List<String>){
        chipGroup.apply {
            isSelectionRequired = true
            isSingleSelection = true
            setOnCheckedStateChangeListener(
                OnCheckChangeListener(context)
            )
        }
        for(category in categories){
            val chip = Chip(this.context)
            chip.apply {
                text = category
                isCheckable = true
                if(category == "waifu") isChecked = true
                setOnClickListener {
                    Toast.makeText(context, category, Toast.LENGTH_SHORT).show()
                }
            }
            chipGroup.addView(chip)
        }
    }
    private suspend fun subscribeUi(adapter: CarouselAdapter) {
        viewModel.waifuList.collect {
            adapter.submitList(it)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.top_app_bar, menu)
    }
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        // TopAppBar Menu redirector
            return when(menuItem.itemId){
                R.id.edit -> {
                    Toast.makeText(context, "edit", Toast.LENGTH_SHORT).show()
                    // TODO: Replace with viewmodel edit favourite list
                    true
                }
                R.id.settings -> {
                    Toast.makeText(context, "settings", Toast.LENGTH_SHORT).show()
                    // TODO: maybe, this has to be handled using Navigation listener
                    true
                }
                else -> false
            }
        }
}

// Manual paging - infinite scroll - just refresh when you reach the end
class OnScrollListener(
    private val lifeCycleScope: CoroutineScope,
    private val adapter: CarouselAdapter,
    private val waifuList: Flow<MutableList<Waifu>>
    ): RecyclerView.OnScrollListener() {
    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        super.onScrolled(recyclerView, dx, dy)

        lifeCycleScope.launch {
            waifuList.collect {
                adapter.submitList(it)
            }
        }
    }
}

// Chip State Listener
class OnCheckChangeListener(
    val context: Context
): ChipGroup.OnCheckedStateChangeListener {
    override fun onCheckedChanged(group: ChipGroup, checkedIds: MutableList<Int>) {
        // CheckedId starts with value 1
        // TODO: ViewModel Functionality to swap sources
        Toast.makeText(context, checkedIds.toString(), Toast.LENGTH_SHORT).show()
    }

}