package one.njk.sao

import one.njk.sao.ui.SaoMotion
import one.njk.sao.ui.screenToolbar

import android.content.Intent
import one.njk.sao.floating.displayName
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
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
import one.njk.sao.usage.*
import one.njk.sao.viewmodels.ArtsViewModel
import javax.inject.Inject

/** The original home carousel, backed by the active installed source rather than a fixed API. */
@OptIn(FlowPreview::class)
@AndroidEntryPoint
class CarouselFragment : Fragment() {
    private var _binding: FragmentCarouselBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val model: ArtsViewModel by activityViewModels()
    private val sources: SourceProfilesViewModel by activityViewModels()
    @Inject lateinit var loader: ImageLoader
    @Inject @HomeSources lateinit var source: MediaSource
    @Inject @HomeSources lateinit var exports: MediaExports
    @Inject lateinit var downloads: MediaDownloads
    @Inject lateinit var clipboard: ClipboardPublisher
    @Inject lateinit var usage: UsageRecorder
    private val filenameRequest=ClipboardRequest()
    @Inject lateinit var preferences: FloatingPreferences
    private var holdMs = 160L
    private lateinit var carousel: PickerCarousel
    private lateinit var previews: CarouselPreviewPrefetcher
    private var categories: List<SourceCategory>? = null
    private var busy = false
    private var titleHold: ResultHoldAction? = null
    private var collectionGesture: HoldDragSelector<SourceProfile>? = null
    private var motion: SaoMotion?=null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCarouselBinding.inflate(inflater,container,false)
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        SaoMotion.surface(view)
        motion=SaoMotion(viewLifecycleOwner)
        screenToolbar(view,R.string.app_name,up=false).apply {
            inflateMenu(R.menu.top_app_bar)
            setOnMenuItemClickListener(::onMenuItemSelected)
        }
        val adapter = MediaCarouselAdapter(loader,viewLifecycleOwner.lifecycleScope,source,
            prefetch=model::loadMore,home=true,holdDelay={ holdMs },canHold={ !busy },held=::preview) { item ->
            val position = carousel.adapter.currentList.indexOfFirst { it.identityKey == item.identityKey }
            if(position >= 0) carousel.view.smoothScrollToPosition(position)
        }
        childFragmentManager.setFragmentResultListener(MediaViewerFragment.TAG,viewLifecycleOwner) { _,_ ->
            adapter.playing=true
        }
        adapter.playing=childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG)==null
        previews=CarouselPreviewPrefetcher(viewLifecycleOwner.lifecycleScope,source::warmPreview)
        carousel = PickerCarousel(binding.carouselRecyclerView,adapter,model::current,{ model.focusedId },
            { key, id, position ->
                model.focus(id); model.loadMore(position)
                if(binding.carouselRecyclerView.scrollState==androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE)
                    previews.update(key,adapter.currentList,position) else previews.pause()
            },::renderActions,{}, { previews.pause() },home=true)
        binding.type.setOnClickListener { chooseSource() }
        collectionGesture = HoldDragSelector(binding.type,requireActivity().findViewById<FrameLayout>(android.R.id.content),
            choices={ sources.state.value.browsingCatalog.profiles }, selected={ sources.state.value.browsingCatalog.let { it.selected ?: it.profiles.first() } },
            label={ it.displayName(requireContext()) }, identity={ sources.state.value.browsingCatalog.identity }, apply={ sources.select(it.id) },
            title=R.string.sources_title,chooseAction=R.string.source_hold_choose,
            nextAction=R.string.source_hold_next,previousAction=R.string.source_hold_previous,
            hint={ getString(R.string.source_hold_release) },holdDelay={ holdMs })
        binding.homeStatus.setOnClickListener {
            if(sources.state.value.browsingCatalog.selected == null) startActivity(Intent(requireContext(),SourceSettingsActivity::class.java))
            else model.retry()
        }
        binding.homeRetry.setOnClickListener { model.retry() }
        binding.homeSourceSettings.setOnClickListener {
            val catalog=sources.state.value.browsingCatalog
            val state=model.state.value.browse
            // A source switch may arrive between rendering the error and handling its click.
            if(!state.error || state.loading || state.sourceId!=catalog.identity) return@setOnClickListener
            val profile=catalog.selected ?: return@setOnClickListener
            if(profile.configuration is AllSourceConfiguration)
                startActivity(Intent(requireContext(),SourceSettingsActivity::class.java))
            else requireActivity().editMediaSource(profile)
        }
        titleHold = ResultHoldAction(binding.mediaTitle,{ !carousel.centeredItem?.filename.isNullOrBlank() },
            { holdMs }, {}, { carousel.centeredItem?.let { item -> action {
                if(source.owns(item)) clipboard.publishFilename(item,filenameRequest,filenameRequest.next(),UsageSurface.HOME)
            } } })
        binding.bottomAppBar.setOnMenuItemClickListener { menu ->
            val item = carousel.centeredItem ?: return@setOnMenuItemClickListener false
            when(menu.itemId) {
                R.id.bookmark -> { model.bookmark(item); Snackbar.make(binding.root,R.string.home_bookmarked,Snackbar.LENGTH_SHORT).show(); true }
                R.id.download -> { action { downloads.save(item,exports.prepare(item,ExportRendition.MEDIA)); usage.record(mediaUsage(UsageAction.DOWNLOAD,UsageSurface.HOME,item)); Snackbar.make(binding.root,R.string.home_saved,Snackbar.LENGTH_SHORT).show() }; true }
                else -> false
            }
        }
        binding.share.setOnClickListener { carousel.centeredItem?.let { item -> action {
            val media = exports.displayed(item)
            startActivity(Intent.createChooser(MediaDelivery.intent(media,null,item.title),getString(R.string.home_share)))
            usage.record(mediaUsage(UsageAction.SHARE,UsageSurface.HOME,item,route=UsageRoute.ATTACHMENT))
        } } }
        renderHome(model.state.value)
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            adapter.playing = childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG)==null
            try { coroutineScope {
                launch { source.changes.sample(1000).collect { model.refreshCurrent() } }
                launch { preferences.options.collect { holdMs=it.longPressMs.toLong() } }
                launch { model.state.collect(::renderHome) }
                launch { sources.state.collect { state ->
                    collectionGesture?.update()
                    binding.type.text = getString(R.string.collection,state.browsingCatalog.selected?.displayName(requireContext()) ?: getString(R.string.picker_source_none))
                    renderStatus()
                } }
            } } finally { previews.pause(); carousel.pause() }
        } }
    }
    private fun preview(item: MediaItem) {
        if(childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(MediaViewerFragment.TAG)!=null) return
        carousel.adapter.playing=false; previews.pause()
        MediaViewerFragment.create(item,model.state.value.browse.sourceId,carousel.adapter.previewBounds(item))
            .show(childFragmentManager,MediaViewerFragment.TAG)
    }
    private fun renderHome(state: one.njk.sao.viewmodels.HomeState) {
        if(binding.carouselRecyclerView.scrollState==androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE)
            previews.update(PickerBrowseKey(state.browse.sourceId,state.browse.query,null),state.browse.items,
                state.browse.items.indexOfFirst { it.identityKey==model.focusedId }.coerceAtLeast(0))
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
    }
    override fun onResume() { super.onResume(); sources.refreshExtensions() }
    override fun onStop() { collectionGesture?.cancel(); super.onStop() }
    private fun renderStatus() {
        if(_binding==null) return
        val state=model.state.value.browse
        val sourceState=sources.state.value
        val emptySource=sourceState.loaded && sourceState.browsingCatalog.selected==null
        val ready=sourceState.loaded && state.sourceId==sourceState.browsingCatalog.identity && !state.loading
        val shown=emptySource || ready && (state.error || state.items.isEmpty())
        motion?.visible(binding.homeStatusPanel,shown)
        binding.homeStatus.isVisible=shown
        binding.homeErrorActions.isVisible=ready && state.error && !emptySource
        binding.homeRetry.isEnabled=!state.loadingMore
        binding.homeSourceSettings.setText(if(sourceState.browsingCatalog.selected?.configuration is AllSourceConfiguration)
            R.string.sources_title else R.string.home_source_settings)
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
            .setSingleChoiceItems(catalog.profiles.map { it.displayName(requireContext()) }.toTypedArray(),catalog.profiles.indexOfFirst { it.id==catalog.selectedId }) { dialog,index ->
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
    override fun onDestroyView() { motion?.close(); motion=null; filenameRequest.next(); titleHold?.cancel(); titleHold=null; collectionGesture?.dispose(); collectionGesture=null; carousel.close(); categories=null; _binding=null; super.onDestroyView() }
    private fun onMenuItemSelected(menuItem: MenuItem): Boolean = when(menuItem.itemId) {
        R.id.usage -> { findNavController().navigate(R.id.action_HomeFragment_to_usageFragment,null,one.njk.sao.ui.platformNavigationOptions(requireContext())); true }
        R.id.settings -> { findNavController().navigate(CarouselFragmentDirections.actionHomeFragmentToSettingsFragment(),one.njk.sao.ui.platformNavigationOptions(requireContext())); true }
        R.id.edit -> { startActivity(Intent(requireContext(),SourceSettingsActivity::class.java)); true }
        else -> false
    }
}
