package one.njk.sao

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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.njk.sao.adapters.CarouselAdapter
import one.njk.sao.databinding.FragmentCarouselBinding
import one.njk.sao.viewmodels.ArtsViewModel

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
@AndroidEntryPoint
class CarouselFragment : Fragment(), MenuProvider {

    private var _binding: FragmentCarouselBinding? = null
    enum class OperatingMode {
        // IGNORE is to prevent actions when pressed directly on the image
        SHARE, DOWNLOAD, IGNORE
    }

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private val viewModel: ArtsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCarouselBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Menu Provider method: https://stackoverflow.com/questions/71917856/sethasoptionsmenuboolean-unit-is-deprecated-deprecated-in-java
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val adapter = CarouselAdapter(viewModel.imageLoader, requireContext(), lifecycleScope) { end ->
            inHousePagination(end)
        }

        _binding!!.apply {
            lifecycleOwner = viewLifecycleOwner
            artsViewModel = viewModel

            bottomAppBar.setOnMenuItemClickListener {
                when(it.itemId){
                    R.id.bookmark -> {
                        // TODO: Replace with viewmodel bookmark to fav list
                        Toast.makeText(context, "bookmark", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.download -> {
                        // No API is exposed to find the current focused child in CarouselLayoutManager
                        val displayMetrics = resources.displayMetrics
                        val x = displayMetrics.widthPixels / 2.3f
                        val y = displayMetrics.heightPixels / 3f

                        if(BuildConfig.DEBUG)
                            Log.d("screen", "${displayMetrics.widthPixels} -> $x")

                        val focusedChild = carouselRecyclerView.findChildViewUnder(x, y)
                        // Since same callback is used for both download and share
                        operatingMode = OperatingMode.DOWNLOAD
                        focusedChild?.performClick()
                        operatingMode = OperatingMode.IGNORE
                        true
                    }
                    else -> false
                }
            }
            share.setOnClickListener {
                // No API was exposed to find the current focused child with Carousel View
                val displayMetrics = resources.displayMetrics
                val x = displayMetrics.widthPixels / 2.3f
                val y = displayMetrics.heightPixels / 3f

                if(BuildConfig.DEBUG)
                    Log.d("screen", "${displayMetrics.widthPixels} -> $x")
                // TODO: This pixel calculation might break in other DPI setting than default

                val focusedChild = carouselRecyclerView.findChildViewUnder(x, y)
                // Since same callback is used for both download and share
                operatingMode = OperatingMode.SHARE
                focusedChild?.performClick()
                operatingMode = OperatingMode.IGNORE
            }

            carouselRecyclerView.adapter = adapter
            subscribeUi(adapter)

            viewModel.availableCategories.observe(viewLifecycleOwner){
                addCategoryChips(categories, it, lifecycleScope)
            }
        }
    }
    private fun inHousePagination(maxLoadedItem: Int){
        if((maxLoadedItem + 3) % 30 == 0){
            viewModel.getNextSetOfWaifus()
        }
    }
    private fun addCategoryChips(
        chipGroup: ChipGroup,
        categories: List<String>,
        lifeCycleScope: CoroutineScope,
    ){
        // Define ChipGroup Behaviours
        chipGroup.apply {
            removeAllViews()
            isSelectionRequired = true
            isSingleSelection = true
        }

        // Generate Chip based on chosen Category (SFW/NSFW)
        for(category in categories){
            val chip = Chip(this.context)
            chip.apply {
                text = category
                isCheckable = true
                setOnClickListener {
                    viewModel.updateType(category)
                    lifeCycleScope.launch {
                        _binding?.carouselRecyclerView?.smoothScrollToPosition(0)
                    }
                }
            }

            chipGroup.addView(chip)
            lifeCycleScope.launch {
                // Check 1st Chip by default
                chipGroup.getChildAt(0).performClick()
            }
        }
    }
    private fun subscribeUi(adapter: CarouselAdapter) {
        viewModel.waifuList.observe(viewLifecycleOwner) {
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
