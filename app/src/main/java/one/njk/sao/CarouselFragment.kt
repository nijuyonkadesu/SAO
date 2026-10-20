package one.njk.sao

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import coil3.ImageLoader
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import one.njk.sao.databinding.FragmentCarouselBinding
import one.njk.sao.floating.*
import one.njk.sao.source.*
import one.njk.sao.viewmodels.ArtsViewModel
import javax.inject.Inject

/** The original home carousel, backed by the active installed source rather than a fixed API. */
@OptIn(FlowPreview::class)
@AndroidEntryPoint
class CarouselFragment : Fragment(), MenuProvider {
    private var _binding: FragmentCarouselBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val model: ArtsViewModel by activityViewModels()
    private val sources: SourceProfilesViewModel by activityViewModels()
    @Inject lateinit var loader: ImageLoader
    @Inject @HomeSources lateinit var source: MediaSource
    @Inject @HomeSources lateinit var exports: MediaExports
    @Inject lateinit var downloads: MediaDownloads
    @Inject lateinit var clipboard: ClipboardPublisher
    private val filenameRequest=ClipboardRequest()
    @Inject lateinit var preferences: FloatingPreferences
    private var holdMs = 160L
    private lateinit var carousel: PickerCarousel
    private lateinit var previews: CarouselPreviewPrefetcher
    private var categories: List<SourceCategory>? = null
    private var busy = false
    private var titleHold: ResultHoldAction? = null
    private var collectionGesture: HoldDragSelector<SourceProfile>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCarouselBinding.inflate(inflater,container,false)
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (requireActivity() as MenuHost).addMenuProvider(this,viewLifecycleOwner,Lifecycle.State.RESUMED)
        val adapter = MediaCarouselAdapter(loader,viewLifecycleOwner.lifecycleScope,source,
            prefetch=model::loadMore,home=true) { item ->
            val position = carousel.adapter.currentList.indexOfFirst { it.id == item.id }
            if(position >= 0) carousel.view.smoothScrollToPosition(position)
        }
        previews=CarouselPreviewPrefetcher(viewLifecycleOwner.lifecycleScope,source::preview)
        carousel = PickerCarousel(binding.carouselRecyclerView,adapter,model::current,{ model.focusedId },
            { key, id, position ->
                model.focus(id); model.loadMore(position)
                if(binding.carouselRecyclerView.scrollState==androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE)
                    previews.update(key,adapter.currentList,position) else previews.pause()
            },::renderActions,{}, { previews.pause() },home=true)
        binding.type.setOnClickListener { chooseSource() }
        collectionGesture = HoldDragSelector(binding.type,requireActivity().findViewById<FrameLayout>(android.R.id.content),
            choices={ sources.state.value.browsingCatalog.profiles }, selected={ sources.state.value.browsingCatalog.let { it.selected ?: it.profiles.first() } },
            label={ it.name }, identity={ sources.state.value.browsingCatalog.identity }, apply={ sources.select(it.id) },
            title=R.string.sources_title,chooseAction=R.string.source_hold_choose,
            nextAction=R.string.source_hold_next,previousAction=R.string.source_hold_previous,
            hint={ getString(R.string.source_hold_release) },holdDelay={ holdMs })
        binding.homeStatus.setOnClickListener {
            if(sources.state.value.browsingCatalog.selected == null) startActivity(Intent(requireContext(),SourceSettingsActivity::class.java))
            else model.retry()
        }
        titleHold = ResultHoldAction(binding.mediaTitle,{ !carousel.centeredItem?.filename.isNullOrBlank() },
            { holdMs }, {}, { carousel.centeredItem?.let { item -> action {
                if(source.owns(item)) clipboard.publishFilename(item,filenameRequest,filenameRequest.next())
            } } })
        binding.bottomAppBar.setOnMenuItemClickListener { menu ->
            val item = carousel.centeredItem ?: return@setOnMenuItemClickListener false
            when(menu.itemId) {
                R.id.bookmark -> { model.bookmark(item); Snackbar.make(binding.root,R.string.home_bookmarked,Snackbar.LENGTH_SHORT).show(); true }
                R.id.download -> { action { downloads.save(item,exports.prepare(item,Rendition.MEDIA)); Snackbar.make(binding.root,R.string.home_saved,Snackbar.LENGTH_SHORT).show() }; true }
                else -> false
            }
        }
        binding.share.setOnClickListener { carousel.centeredItem?.let { item -> action {
            val media = exports.displayed(item)
            startActivity(Intent.createChooser(MediaDelivery.intent(media,null,item.title),getString(R.string.home_share)))
        } } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            adapter.playing = true
            try { coroutineScope {
                launch { source.changes.sample(1000).collect { model.refreshCurrent() } }
                launch { preferences.options.collect { holdMs=it.longPressMs.toLong() } }
                launch { model.state.collect { state ->
                    if(binding.carouselRecyclerView.scrollState==androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE)
                        previews.update(PickerBrowseKey(state.browse.sourceId,state.browse.query,null),state.browse.items,
                            state.browse.items.indexOfFirst { it.id==model.focusedId }.coerceAtLeast(0))
                    else previews.pause()
                    carousel.render(state.browse)
                    if(categories != state.categories) {
                        categories = state.categories
                        binding.categories.apply {
                            removeAllViews(); isSingleSelection=true; isSelectionRequired=true
                            state.categories.forEach { category ->
                                addView(Chip(context).apply {
                                    id=View.generateViewId(); text=category.label; isCheckable=true
                                    tag=category.id; setOnClickListener { model.chooseCategory(category.id) }
                                })
                            }
                        }
                    }
                    for(i in 0 until binding.categories.childCount) (binding.categories.getChildAt(i) as Chip).apply {
                        isChecked = tag == state.categoryId
                    }
                    renderStatus(); renderActions()
                } }
                launch { sources.state.collect { state ->
                    collectionGesture?.update()
                    binding.type.text = getString(R.string.collection,state.browsingCatalog.selected?.name ?: getString(R.string.picker_source_none))
                    renderStatus()
                } }
            } } finally { previews.pause(); carousel.pause() }
        } }
    }
    override fun onResume() { super.onResume(); sources.refreshExtensions() }
    override fun onStop() { collectionGesture?.cancel(); super.onStop() }
    private fun renderStatus() {
        if(_binding==null) return
        val state=model.state.value.browse
        val sourceState=sources.state.value
        val emptySource=sourceState.loaded && sourceState.browsingCatalog.selected==null
        val ready=sourceState.loaded && state.sourceId==sourceState.browsingCatalog.identity && !state.loading
        binding.homeStatus.isVisible = emptySource || ready && (state.error || state.items.isEmpty())
        binding.homeStatus.text = when {
            emptySource -> getString(R.string.home_add_source)
            state.error -> getString(state.errorMessage ?: R.string.home_retry)
            else -> getString(R.string.home_empty)
        }
    }
    private fun renderActions() {
        if(_binding==null) return
        val item=carousel.centeredItem
        binding.mediaTitle.text=item?.title.orEmpty()
        binding.mediaTitle.contentDescription=item?.filename ?: item?.title
        binding.share.isEnabled=item!=null && !busy
        binding.bottomAppBar.menu.findItem(R.id.download).isEnabled=item!=null && !busy
        binding.bottomAppBar.menu.findItem(R.id.bookmark).isEnabled=item!=null && !busy
    }
    private fun chooseSource() {
        val catalog=sources.state.value.browsingCatalog
        if(catalog.profiles.isEmpty()) { startActivity(Intent(requireContext(),SourceSettingsActivity::class.java)); return }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.sources_title)
            .setSingleChoiceItems(catalog.profiles.map { it.name }.toTypedArray(),catalog.profiles.indexOfFirst { it.id==catalog.selectedId }) { dialog,index ->
                sources.select(catalog.profiles[index].id); dialog.dismiss()
            }.setNeutralButton(R.string.sources_title) { _,_ -> startActivity(Intent(requireContext(),SourceSettingsActivity::class.java)) }
            .setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun action(block: suspend () -> Unit) {
        if(busy) return
        busy=true; renderActions()
        viewLifecycleOwner.lifecycleScope.launch {
            try { block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { if(_binding!=null) Snackbar.make(binding.root,R.string.home_action_failed,Snackbar.LENGTH_LONG).show() }
            finally { busy=false; renderActions() }
        }
    }
    override fun onDestroyView() { filenameRequest.next(); titleHold?.cancel(); titleHold=null; collectionGesture?.dispose(); collectionGesture=null; carousel.close(); categories=null; _binding=null; super.onDestroyView() }
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) { menuInflater.inflate(R.menu.top_app_bar,menu) }
    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when(menuItem.itemId) {
        R.id.settings -> { findNavController().navigate(CarouselFragmentDirections.actionHomeFragmentToSettingsFragment()); true }
        R.id.edit -> { startActivity(Intent(requireContext(),SourceSettingsActivity::class.java)); true }
        else -> false
    }
}
