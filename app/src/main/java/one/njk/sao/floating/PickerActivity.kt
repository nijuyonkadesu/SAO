package one.njk.sao.floating

import one.njk.sao.ui.SaoMotion

import one.njk.sao.source.*
import one.njk.sao.usage.*

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import one.njk.sao.R
import one.njk.sao.databinding.ActivityPickerBinding
import one.njk.sao.floating.nativebridge.NativeBridgeRegistry
import javax.inject.Inject

@AndroidEntryPoint
class PickerActivity : AppCompatActivity() {
    private val motion by lazy { SaoMotion(this) }
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var exports: MediaExports
    @Inject lateinit var source: MediaSource
    @Inject lateinit var dispatchers: FloatingDispatchers
    @Inject lateinit var apps: AppCatalog
    @Inject lateinit var usage: UsageRecorder
    @Inject lateinit var emojiKeywords: one.njk.sao.floating.emoji.EmojiKeywords
    @Inject lateinit var suggestionRequests: one.njk.sao.floating.search.SuggestionRequests
    private val model: PickerViewModel by viewModels()
    private val sourcesModel: SourceProfilesViewModel by viewModels()
    private val searchOptions: SearchOptionsViewModel by viewModels()
    private lateinit var searchController: PickerSearchController
    private data class SourceGestureChoice(val id: String?, val name: String)
    private lateinit var sourceGesture: HoldDragSelector<SourceGestureChoice>
    private lateinit var windowMotion: PickerWindowMotion
    private var imeHeight = 0
    private lateinit var binding: ActivityPickerBinding
    private lateinit var dockMarquees: PickerMarquees
    private lateinit var resultsCarousel: PickerCarousel
    private lateinit var sequenceCarousel: PickerCarousel
    private val carousel get() = if (renderedSequence == null) resultsCarousel else sequenceCarousel
    private val adapter get() = carousel.adapter
    private var targetPackage: String? = null
    private var pasteAnchor: String? = null
    private var nativeSession: String? = null
    private var targetName: String? = null
    private var busy = false
    private var syncingQuery = false
    private var editing = false
    private var hadIme = false
    private var compactEditing = false
    private var compactSequence = false
    private var dockLayoutMode = -1
    private var modeChipWidth = 0
    private var renderedSequence: String? = null
    private var options = FloatingOptions()
    private var canShareSticker = false
    private var canAttachInstagram = false
    private var capabilityJob: Job? = null
    private var capabilityMime: String? = null
    private var catalog = SourceCatalog()
    private var completionCapabilities = SourceCapabilities()
    private var appChoices: List<AppChoice> = emptyList()
    private lateinit var metrics: android.view.WindowMetrics
    private val target get() = AppFamilyResolver.resolve(targetPackage, options.telegramPackages)

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        readContext()
        binding = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dockMarquees = PickerMarquees(lifecycleScope, binding.selectionTitle, binding.selectionMessage)
        ResultHoldAction(binding.selectionTitle, { !carousel.centeredItem?.filename.isNullOrBlank() },
            { options.longPressMs.toLong() }, {}, { carousel.centeredItem?.let(model::copyFilename) })
        window.setLayout(-1, -1)
        metrics = windowManager.maximumWindowMetrics
        binding.root.setOnClickListener { finish() }
        binding.pickerPanel.setOnClickListener { }
        binding.close.setOnClickListener { finish() }
        binding.sourceToggle.setOnClickListener { collapseSearch(); showSource(!binding.sourcePanel.isVisible) }
        binding.sourceBack.setOnClickListener { showSource(false) }
        binding.sourceConfigure.setOnClickListener { addMediaSource(floating = true) }
        binding.addFirstSource.setOnClickListener {
            if (sourcesModel.state.value.catalog.profiles.isNotEmpty()) startActivity(Intent(this, SourceSettingsActivity::class.java))
            else addMediaSource(floating = true)
        }
        val sourceAdapter = SourceListAdapter({ id -> sourcesModel.selectFloating(id) }) { id ->
            editMediaSource(catalog.profiles.firstOrNull { it.id == id }, floating = true)
        }
        binding.sourceList.layoutManager = LinearLayoutManager(this); binding.sourceList.adapter = sourceAdapter
        binding.message.setOnClickListener { model.retry() }
        binding.sequenceBack.setOnClickListener {
            collapseSearch(); showSource(false); showTargets(false); model.backToResults()
        }
        binding.searchToggle.setOnClickListener {
            if (editing && searchController.controlsAvailable && !binding.searchControls.isVisible) searchController.open()
            else if (editing) collapseSearch() else expandSearch()
        }
        fun createCarousel(view: RecyclerView, sequence: Boolean): PickerCarousel {
            val media = MediaCarouselAdapter(imageLoader, lifecycleScope, source, prefetch = { position ->
                if (!sequence && model.currentSequenceId == null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    view.isShown && !compactEditing) model.loadMore(position)
            }, holdDelay = { options.longPressMs.toLong() },
                canHold = { !sequence && model.currentSequenceId == null && options.browseSequence && model.canBrowseSequence.value && !busy &&
                    !model.state.value.loading && !model.state.value.loadingMore && model.state.value.query == model.query.value }, held = { item ->
                    recordFocus(); collapseSearch(); model.browseSequence(item)
                }) { item ->
                if (!busy) {
                    collapseSearch(); carousel.adapter.focusedId = item.identityKey
                    carousel.view.smoothScrollToPosition(carousel.adapter.currentList.indexOfFirst { it.identityKey == item.identityKey })
                    model.select(item, target)
                }
            }
            media.showUploadTime = sequence
            return PickerCarousel(view, media, current = { key -> model.isCurrentBrowse(key) },
                focused = { key -> model.focusForBrowse(key.sequenceId) },
                focusChanged = { key, id, position ->
                    model.focus(id, key.sequenceId); model.loadMore(position)
                }, centerChanged = ::centerChanged, remember = model::rememberBrowsing, dragging = ::collapseSearch)
        }
        resultsCarousel = createCarousel(binding.mediaGrid, false)
        sequenceCarousel = createCarousel(binding.sequenceGrid, true)
        searchController = PickerSearchController(this, binding, model, searchOptions, source, imageLoader,motion,
            visibilityChanged = { visible ->
                if (visible) binding.search.dismissSuggestions()
                if (visible) { showSource(false); showTargets(false); binding.message.isVisible = false }
                showCarousels()
                resize(imeHeight)
            }, redraw = { renderHeader(); resize(imeHeight) }, randomRequested = { collapseSearch(); model.lucky() },
            beforeGesture = { binding.search.dismissSuggestions(); if (::sourceGesture.isInitialized) sourceGesture.cancel() })
        sourceGesture = HoldDragSelector(binding.sourceToggle, binding.root as FrameLayout,
            choices = ::sourceGestureChoices,
            selected = { SourceGestureChoice(catalog.selectedId, catalog.selected?.displayName(this) ?: getString(R.string.picker_source_none)) },
            label = { it.name }, identity = { catalog.identity },
            apply = { choice ->
                showSource(false)
                sourcesModel.selectFloating(choice.id)
            }, title = R.string.sources_title, chooseAction = R.string.source_hold_choose,
            nextAction = R.string.source_hold_next, previousAction = R.string.source_hold_previous,
            opening = { binding.search.dismissSuggestions(); searchController.cancelGesture() },
            hint = { getString(R.string.source_hold_release) }, holdDelay = { options.longPressMs.toLong() })
        binding.search.bind(emojiKeywords, this, binding.searchField) {
            editing && !searchController.visible && !binding.sourcePanel.isVisible && !binding.targetList.isVisible
        }
        binding.search.sourceSuggestions(context = {
            val query=model.query.value
            if(completionCapabilities.sourceId==catalog.identity && query.mode in completionCapabilities.suggestionModes)
                one.njk.sao.floating.search.SourceCompletionContext(catalog.identity,query) else null
        }) { context,cursor -> suggestionRequests.run(context.sourceId,cursor) {
            val answer = source.withSource(context.sourceId) { it.suggestions(context.query,cursor) }
            check(catalog.identity==context.sourceId) { "Source changed" }
            answer
        } }
        binding.search.setText(model.query.value.text)
        binding.search.doAfterTextChanged {
            if (!syncingQuery) model.search(it?.toString().orEmpty())
            renderHeader()
        }
        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                if (!binding.search.completeHighlightedSuggestion()) { model.backToResults(); collapseSearch() }
                true
            } else false
        }
        binding.preferStickers.setOnClickListener {
            // This chip names the active mode, so both modes keep their active checkmark.
            binding.preferStickers.isChecked = true
            collapseSearch(); model.preferStickers(!options.preferStickers)
        }
        binding.copyAction.setOnClickListener { withCenteredItem { model.copy(target) } }
        binding.pasteAction.setOnClickListener { withCenteredItem { item -> dispatch {
            val rendition = nativePasteRendition(item)
            if (rendition != null) {
                nativeInsert(item, rendition)
            } else {
                val event=mediaUsage(UsageAction.PASTE,UsageSurface.PICKER,item,target.usageTarget(),UsageRoute.ACCESSIBILITY)
                if(model.prepareClipboard(target) { limitAttachment(item,it) } && PasteAssistService.requestPaste(pasteAnchor,targetPackage,event)) finish()
                else { usage.record(event.copy(outcome=UsageOutcome.FAILED)); inlineFailure(R.string.floating_paste_unavailable) }
            }
        } } }
        binding.mediaAction.setOnClickListener { withCenteredItem { item -> dispatch { share(item, ExportRendition.MEDIA) } } }
        binding.stickerAction.setOnClickListener { withCenteredItem { item -> dispatch {
            if (target == MessagingTarget.TELEGRAM) share(item, ExportRendition.STICKER_WEBP) else nativeInsert(item,action=UsageAction.STICKER)
        } } }
        binding.targetLabel.setOnClickListener {
            if (target == MessagingTarget.OTHER && appChoices.isNotEmpty()) {
                collapseSearch(); showTargets(!binding.targetList.isVisible)
            }
        }
        binding.targetList.layoutManager = LinearLayoutManager(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when { cancelPickerGesture() -> Unit
                    binding.search.dismissSuggestions() -> Unit
                    searchController.visible && hadIme -> searchController.hideKeyboard()
                    searchController.visible -> searchController.close()
                    editing -> collapseSearch(); binding.sourcePanel.isVisible -> showSource(false)
                    binding.targetList.isVisible -> showTargets(false)
                    model.backToResults() -> Unit; else -> finish() }
            }
        })
        windowMotion=PickerWindowMotion(this,binding.root,binding.pickerPanel) { updateCarouselStrategy() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            if(isFinishing || isDestroyed) return@setOnApplyWindowInsetsListener insets
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (hadIme && ime == 0 && editing && !searchController.visible) collapseSearch()
            hadIme = ime > 0; resize(ime); insets
        }
        resize(0)
        binding.filters.setOnCheckedStateChangeListener { group, ids ->
            if (!syncingQuery) {
                collapseSearch(); showSource(false)
                model.emojiShortcut(ids.firstOrNull()?.let { group.findViewById<Chip>(it).text.toString() }.orEmpty())
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.query.collect { syncQueryViews(it); renderHeader(); renderDock() } }
                launch { source.capabilities.collect { completionCapabilities=it; binding.search.refreshSuggestions() } }
                launch { sourcesModel.state.collect { state ->
                    catalog = state.floatingCatalog
                    sourceGesture.update()
                    sourceAdapter.submitList(sourceRows(catalog))
                    binding.sourceSummary.isVisible = state.error
                    binding.sourceConfigure.isEnabled = state.loaded && !state.saving
                    binding.sourceToggle.isEnabled = state.loaded && !state.saving
                    renderHeader(); renderEmojiShortcuts(); renderEmptySource()
                } }
                launch { model.state.collect { state ->
                    searchController.renderLoading(state)
                    if (renderedSequence != state.sequenceId) {
                        carousel.pause()
                        renderedSequence = state.sequenceId
                        if (state.sequenceId == null) sequenceCarousel.clear()
                        showCarousels(); renderHeader(); resize(imeHeight)
                    }
                    if (!state.loading && model.selection.value?.identityKey?.let { id -> state.items.none { it.identityKey == id } } == true) model.clearSelection()
                    if (state.query == model.query.value)
                        carousel.render(state)
                    updatePlayback()
                    showProgress(busy || state.loading || state.loadingMore)
                    binding.message.isVisible = state.error || (!state.loading && state.items.isEmpty())
                    binding.message.setText(state.errorMessage ?: if (state.error) {
                        if (state.sequenceId != null) R.string.sequence_retry
                        else if (state.items.isEmpty()) R.string.picker_retry else R.string.picker_retry_more
                    } else R.string.floating_no_results)
                    binding.message.isClickable = state.error
                    binding.message.layoutParams = FrameLayout.LayoutParams(-1,
                        if (state.error && state.items.isNotEmpty()) -2 else -1,
                        if (state.error && state.items.isNotEmpty()) Gravity.BOTTOM else Gravity.CENTER)
                    binding.message.minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    binding.message.isFocusable = state.error
                    binding.message.isVisible = binding.message.isVisible && !binding.sourcePanel.isVisible && !binding.targetList.isVisible && !searchController.visible
                    renderDock(); renderEmptySource()
                } }
                launch { model.canBrowseSequence.collect { /* Keep source capability live only while the picker is visible. */ } }
                launch { NativeBridgeRegistry.sessions.collect { renderDock() } }
                launch { NativeBridgeRegistry.statuses.collect { renderDock() } }
                launch { model.selection.collect {
                    resultsCarousel.adapter.selectedId = it?.identityKey
                    sequenceCarousel.adapter.selectedId = it?.identityKey
                    renderDock()
                } }
                launch { model.copyStatus.collect { renderDock() } }
                launch { model.options.collect { value ->
                    val mappingChanged = options.telegramPackages != value.telegramPackages
                    options = value
                    renderEmojiShortcuts()
                    binding.preferStickers.isChecked = true
                    binding.preferStickers.setText(if (value.preferStickers) R.string.picker_sticker else R.string.picker_media)
                    reserveModeWidth()
                    binding.preferStickers.contentDescription = getString(R.string.picker_delivery_mode,
                        getString(if (value.preferStickers) R.string.picker_sticker else R.string.picker_media),
                        getString(if (value.preferStickers) R.string.picker_media else R.string.picker_sticker))
                    if (mappingChanged || appChoices.isEmpty()) loadTargets()
                    refreshCapability(); renderDock(); renderHeader()
                } }
            }
        }
    }
    private fun readContext() {
        targetPackage = intent.getStringExtra(EXTRA_TARGET); pasteAnchor = intent.getStringExtra(EXTRA_ANCHOR)
        nativeSession = intent.getStringExtra(EXTRA_NATIVE); targetName = null
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); readContext(); refreshCapability(); renderHeader(); renderDock()
    }
    override fun onResume() { super.onResume(); sourcesModel.refreshExtensions() }
    override fun onStart() {
        super.onStart()
        dockMarquee(true)
        binding.root.post { dockMarquees.refresh() }
        model.pickerVisible(!isFinishing)
        refreshCapability()
        updatePlayback()
    }
    override fun finish() {
        if(::windowMotion.isInitialized) windowMotion.freezeForExit()
        super.finish()
        // Restore on the dismissal request, before exit animation / preview cleanup / onStop.
        if (isFinishing) model.pickerVisible(false)
    }
    override fun onStop() {
        dockMarquee(false)
        // The retained ViewModel keeps one owner during recreation, so the icon never flashes.
        // Backgrounding/covering a picker still releases it through the ordinary lifecycle.
        if (!isChangingConfigurations) model.pickerVisible(false)
        cancelPickerGesture(); recordFocus(); model.rememberBrowsing(); resultsCarousel.pause(); sequenceCarousel.pause()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        recordFocus(); model.rememberBrowsing()
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { sourceGesture.dispose(); searchController.dispose(); resultsCarousel.close(); sequenceCarousel.close(); super.onDestroy() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        cancelPickerGesture()
        super.onConfigurationChanged(newConfig); metrics = windowManager.maximumWindowMetrics; resize(0)
    }
    private fun recordFocus() = carousel.recordFocus()
    internal fun dockMarquee(active: Boolean) = dockMarquees.setEnabled(active)
    private fun updateMessageVisibility() {
        // Invisible reserves one font-scaled line; only a different UI mode removes the row.
        binding.selectionMessage.visibility = when {
            searchController.visible || compactEditing || compactSequence -> View.GONE
            binding.selectionMessage.text.isEmpty() -> View.INVISIBLE
            else -> View.VISIBLE
        }
        dockMarquees.refresh()
    }
    private fun centerChanged() {
        val mime = carousel.centeredItem?.mimeType
        if (target == MessagingTarget.INSTAGRAM && mime != null && mime != capabilityMime) refreshCapability()
        renderDock()
    }
    private fun withCenteredItem(action: (MediaItem) -> Unit) {
        if (busy) return
        recordFocus()
        val item = carousel.centeredItem ?: return
        model.selectForAction(item)
        collapseSearch(); action(item)
    }
    private fun updatePlayback() {
        val playing = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !compactEditing &&
            !binding.targetList.isVisible && !binding.sourcePanel.isVisible && !searchController.visible
        resultsCarousel.adapter.playing = playing && renderedSequence == null
        sequenceCarousel.adapter.playing = playing && renderedSequence != null
    }
    private fun showCarousels() {
        renderEmptySource()
        val visible = !searchController.visible && !binding.sourcePanel.isVisible && !binding.targetList.isVisible
        binding.mediaGrid.isVisible = visible && renderedSequence == null
        binding.sequenceGrid.isVisible = visible && renderedSequence != null
    }
    private fun syncQueryViews(query: MediaQuery) {
        syncingQuery = true
        if (binding.search.text?.toString() != query.text) binding.search.setText(query.text)
        for (i in 0 until binding.filters.childCount) {
            val chip = binding.filters.getChildAt(i) as Chip
            chip.isChecked = chip.text.toString() == query.text
        }
        syncingQuery = false
        binding.search.refreshSuggestions()
        searchController.renderQuery()
    }
    private fun expandSearch() {
        sourceGesture.cancel()
        motion.layout(binding.pickerHeader)
        showSource(false); showTargets(false); motion.visible(binding.sourceToggle,false)
        editing = true; motion.visible(binding.targetLabel,false); motion.visible(binding.searchField,true)
        renderHeader(); resize(imeHeight)
        binding.search.requestFocus(); binding.search.selectAll()
        WindowCompat.getInsetsController(window, binding.search).show(WindowInsetsCompat.Type.ime())
    }
    private fun collapseSearch() {
        binding.search.dismissSuggestions()
        if (!editing) return
        searchController.close()
        motion.layout(binding.pickerHeader)
        editing = false; binding.search.clearFocus(); motion.visible(binding.searchField,false); motion.visible(binding.targetLabel,true)
        WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime()); renderHeader(); resize(imeHeight)
    }
    private fun renderHeader() {
        if (!::binding.isInitialized) return
        val name = targetName ?: when (target) {
            MessagingTarget.WHATSAPP -> "WhatsApp"; MessagingTarget.WHATSAPP_BUSINESS -> "WhatsApp Business"
            MessagingTarget.TELEGRAM -> targetPackage; MessagingTarget.DISCORD -> "Discord"
            MessagingTarget.INSTAGRAM -> "Instagram"; else -> null
        }
        val query = model.query.value.text.trim()
        val pickApp = target == MessagingTarget.OTHER && appChoices.isNotEmpty()
        val heading = name?.let { getString(R.string.picker_target, it) } ?: getString(R.string.picker_clipboard)
        val detail = when {
            model.state.value.sequenceId != null -> getString(R.string.sequence_title)
            query.isNotEmpty() -> getString(R.string.picker_query_label,query)
            model.query.value.randomRequest != null -> getString(R.string.search_random_results)
            else -> null
        }
        binding.targetLabel.text = when {
            detail != null -> getString(R.string.label_with_detail,heading,detail)
            pickApp -> getString(R.string.picker_dropdown_label,heading)
            else -> heading
        }
        binding.targetLabel.contentDescription = binding.targetLabel.text.toString() + if (pickApp) ", ${getString(R.string.picker_choose_target)}" else ""
        val activeFilters = model.query.value.constraints.forSource(searchOptions.state.value.sourceId).count
        binding.searchToggle.isActivated = query.isNotEmpty() || model.query.value.randomRequest != null || (searchController.supported &&
            (activeFilters > 0 || model.query.value.mode != SearchMode.CONTEXT))
        val compactSearch = editing && searchController.controlsAvailable && !binding.searchControls.isVisible && !searchController.visible
        binding.searchToggle.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this,
            if (compactSearch) R.drawable.ic_picker_filter else R.drawable.ic_picker_search)
        binding.searchToggle.contentDescription = getString(if (compactSearch) R.string.search_options else R.string.floating_search_hint) + if (query.isNotEmpty()) ": $query" else ""
        binding.sourceToggle.isVisible = !editing
        val label = catalog.selected?.displayName(this) ?: getString(R.string.picker_source_none)
        val density = resources.displayMetrics.density
        val headerWidth = binding.pickerHeader.width.takeIf { it > 0 } ?: ((metrics.bounds.width() / density - 48) * density).toInt()
        val desired = binding.sourceToggle.paint.measureText("$label ▾") + 48 * density
        val iconOnly = desired > headerWidth - (96 + 72) * density
        val sourceText = if (iconOnly) "" else "$label ▾"
        if(binding.sourceToggle.text.toString()!=sourceText) {
            motion.layout(binding.pickerHeader)
            binding.sourceToggle.text=sourceText
        }
        val sourceWidth = if (iconOnly) (48 * density).toInt() else -2
        if (binding.sourceToggle.layoutParams.width != sourceWidth)
            binding.sourceToggle.layoutParams = binding.sourceToggle.layoutParams.apply { width = sourceWidth }
        binding.sourceToggle.iconPadding = if (iconOnly) 0 else (4 * density).toInt()
        binding.sourceToggle.contentDescription = getString(R.string.source_hold_accessibility, label)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.sourceToggle, binding.sourceToggle.contentDescription)
    }
    private fun nativeUnavailableHint(video: Boolean = false): String {
        val status = NativeBridgeRegistry.status(targetPackage)
        if (!NativeBridgeRegistry.available(targetPackage)) return getString(R.string.picker_native_inactive)
        if (video && target == MessagingTarget.INSTAGRAM && status != null) {
            val reason = getString(when {
                status.revision < NativeBridgeRegistry.PROTOCOL_REVISION && status.version.isNotEmpty() -> R.string.picker_instagram_hooks_old
                status.stage == "selection_timeout" -> R.string.picker_instagram_capture_timeout
                status.videoStage == "gallery_loading" -> R.string.picker_instagram_gallery_loading
                status.videoStage == "gallery_unavailable" -> R.string.picker_instagram_gallery_unavailable
                status.videoStage == "thread_unresolved" -> R.string.picker_instagram_thread_unresolved
                status.videoStage == "media_restricted" -> R.string.picker_instagram_media_restricted
                status.videoStage == "sender_missing" -> R.string.picker_instagram_sender_missing
                status.videoStage == "ambiguous_composer" -> R.string.picker_instagram_composer_ambiguous
                status.videoStage == "pending_metadata" || status.stage == "pending_metadata" -> R.string.picker_native_metadata_pending
                status.stage == "hooks_unavailable" -> R.string.picker_native_hooks_unavailable
                status.videoStage == "ready" -> R.string.picker_instagram_video_reopen
                else -> R.string.picker_native_focus
            })
            val version = status.version.ifEmpty { "—" } + status.build.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
            return getString(R.string.picker_instagram_video_diagnostic, reason, version)
        }
        val reason = getString(when (status?.stage) {
            "hooks_unavailable" -> R.string.picker_native_hooks_unavailable
            "pending_metadata" -> R.string.picker_native_metadata_pending
            "unsupported_mime" -> R.string.picker_native_mime_unsupported
            "ready", "unfocused" -> R.string.picker_native_reopen
            else -> R.string.picker_native_focus
        })
        return getString(R.string.picker_native_diagnostic, status?.version.orEmpty().ifEmpty { "—" }, reason)
    }
    private fun nativePasteRendition(item: MediaItem?): ExportRendition? {
        if (item == null) return null
        if (target.isWhatsapp && options.preferStickers && !item.hasMotion &&
            NativeBridgeRegistry.supports(targetPackage, nativeSession, "image/webp.wasticker")) return ExportRendition.STICKER_WEBP
        val mime = nativeMediaMime(item)
        return if ((target.isWhatsapp || target == MessagingTarget.INSTAGRAM || target == MessagingTarget.DISCORD) &&
            NativeBridgeRegistry.supports(targetPackage, nativeSession, mime)) ExportRendition.MEDIA else null
    }
    private fun nativeMediaMime(item: MediaItem): String {
        val original = item.mimeType
        return VideoCompatibility.mime(original) { NativeBridgeRegistry.supports(targetPackage, nativeSession, it) }
    }
    private fun renderDock() {
        if (!::binding.isInitialized) return
        val item = carousel.centeredItem
        val title = item?.title ?: getString(R.string.picker_choose)
        val status = if (item == null || model.selection.value?.identityKey != item.identityKey || model.copyStatus.value == R.string.picker_choose)
            "" else getString(model.copyStatus.value)
        dockMarquees.text(binding.selectionTitle, title)
        binding.selectionTitle.contentDescription = title
        binding.copyAction.isEnabled = item != null && !busy
        val nativeRendition = nativePasteRendition(item)
        val nativePaste = nativeRendition != null
        val nativePhoto = nativePaste && target == MessagingTarget.INSTAGRAM && item?.isVideo != true
        binding.pasteAction.isEnabled = !busy && (nativePaste || item != null &&
            DeliveryPolicy.supportsPaste(item, target) && target != MessagingTarget.OTHER &&
            FloatingSession.active.value && options.pasteAssistance && pasteAnchor != null && PasteAssistService.connected())
        val native = NativeBridgeRegistry.supports(targetPackage, nativeSession, "image/webp.wasticker")
        binding.stickerAction.isEnabled = item != null && !item.hasMotion && !busy &&
            (target == MessagingTarget.TELEGRAM && canShareSticker || target.isWhatsapp && native)
        binding.mediaAction.isEnabled = item != null && !busy && (nativePaste || target != MessagingTarget.INSTAGRAM || canAttachInstagram)
        val mediaLabel = getString(when (target) {
            MessagingTarget.DISCORD -> R.string.picker_attach
            MessagingTarget.INSTAGRAM -> R.string.picker_instagram_dm
            else -> R.string.picker_media
        })
        if (binding.mediaAction.text.toString() != mediaLabel) binding.mediaAction.text = mediaLabel
        val hint = when {
            nativePaste && target == MessagingTarget.INSTAGRAM && item?.isVideo == true -> getString(R.string.picker_instagram_native_video_hint)
            nativePhoto -> getString(R.string.picker_instagram_native_hint)
            target.isWhatsapp && nativeRendition == ExportRendition.STICKER_WEBP -> getString(R.string.picker_whatsapp_native_paste_hint)
            nativePaste && target == MessagingTarget.DISCORD -> getString(R.string.picker_discord_native_hint)
            nativePaste -> getString(R.string.picker_native_image_hint)
            target == MessagingTarget.INSTAGRAM && item?.isVideo == true && NativeBridgeRegistry.available(targetPackage) -> nativeUnavailableHint(video = true)
            target == MessagingTarget.INSTAGRAM && item?.animated == true && NativeBridgeRegistry.available(targetPackage) -> getString(R.string.picker_instagram_motion_hint)
            target == MessagingTarget.INSTAGRAM && !canAttachInstagram -> getString(R.string.picker_instagram_unavailable)
            target == MessagingTarget.INSTAGRAM && NativeBridgeRegistry.available(targetPackage) -> nativeUnavailableHint()
            target == MessagingTarget.INSTAGRAM -> getString(R.string.picker_instagram_hint)
            target == MessagingTarget.DISCORD && item?.isVideo == true -> getString(R.string.picker_discord_motion_hint)
            target == MessagingTarget.DISCORD && !binding.pasteAction.isEnabled && NativeBridgeRegistry.available(targetPackage) -> nativeUnavailableHint()
            target == MessagingTarget.DISCORD && !binding.pasteAction.isEnabled -> getString(R.string.picker_discord_paste_inactive)
            target == MessagingTarget.DISCORD -> getString(R.string.picker_discord_hint)
            item?.isVideo == true -> getString(R.string.picker_video_hint)
            item?.animated == true -> getString(R.string.picker_gif_hint)
            target.isWhatsapp && !native -> nativeUnavailableHint()
            target == MessagingTarget.TELEGRAM && !canShareSticker -> getString(R.string.floating_app_unavailable)
            !binding.pasteAction.isEnabled -> getString(R.string.picker_assist_hint)
            else -> ""
        }
        val message = when {
            status.isEmpty() -> hint
            hint.isEmpty() -> status
            else -> getString(R.string.picker_dock_message, status, hint)
        }
        dockMarquees.text(binding.selectionMessage, message)
        binding.selectionMessage.contentDescription = message
        updateMessageVisibility()
        if (target != MessagingTarget.OTHER && binding.targetList.isVisible) showTargets(false)
        updateActionAccessibility()
    }
    private fun updateActionAccessibility() {
        val destination = targetName?.let { ": $it" }.orEmpty()
        binding.pasteAction.contentDescription = getString(if (target == MessagingTarget.INSTAGRAM)
            R.string.picker_instagram_paste_description else R.string.picker_paste_description) + destination
        binding.stickerAction.contentDescription = getString(R.string.picker_send_sticker_description) + destination
        val nativeMedia = nativePasteRendition(carousel.centeredItem) != null
        binding.mediaAction.contentDescription = getString(when {
            nativeMedia && target == MessagingTarget.INSTAGRAM -> R.string.picker_instagram_native_dm_description
            nativeMedia && target == MessagingTarget.DISCORD -> R.string.picker_discord_native_attach_description
            target == MessagingTarget.DISCORD -> R.string.picker_discord_attach_description
            target == MessagingTarget.INSTAGRAM -> R.string.picker_instagram_dm_description
            else -> R.string.picker_share_media_description
        }) + destination
    }
    private fun reserveModeWidth() {
        val chip = binding.preferStickers
        if (modeChipWidth == 0) {
            val current = chip.text
            val longest = listOf(getString(R.string.picker_sticker), getString(R.string.picker_media))
                .maxBy { chip.paint.measureText(it) }
            // Measure once using the drawable's own typography/checked-icon metrics, before drawing.
            chip.text = longest
            modeChipWidth = kotlin.math.ceil((chip.chipDrawable?.intrinsicWidth ?: 0) +
                4 * resources.displayMetrics.density).toInt()
            chip.text = current
        }
        chip.minWidth = modeChipWidth
    }
    private fun showTargets(show: Boolean) {
        if (show) { sourceGesture.cancel(); searchController.close(); motion.visible(binding.sourcePanel,false) }
        motion.visible(binding.targetList,show); showCarousels()
        updatePlayback()
        if (show) binding.message.isVisible = false
    }
    private fun showSource(show: Boolean) {
        if (show) { sourceGesture.cancel(); searchController.close(); motion.visible(binding.targetList,false) }
        motion.visible(binding.sourcePanel,show); showCarousels()
        updatePlayback()
        binding.message.isVisible = catalog.selected!=null && !show && !binding.targetList.isVisible && !searchController.visible &&
            (model.state.value.error || (!model.state.value.loading && model.state.value.items.isEmpty()))
    }
    private fun renderEmojiShortcuts() {
        val values=if(options.showEmojiShortcuts) EmojiShortcuts.parse(options.emojiShortcuts) else emptyList()
        val current=(0 until binding.filters.childCount).map { (binding.filters.getChildAt(it) as Chip).text.toString() }
        syncingQuery=true
        if(current!=values) {
            binding.filters.removeAllViews()
            values.forEach { value -> binding.filters.addView(Chip(this).apply {
                id=View.generateViewId(); text=value; isCheckable=true
                checkedIcon=androidx.appcompat.content.res.AppCompatResources.getDrawable(context,R.drawable.ic_picker_check)
                checkedIconTint=textColors
            }) }
        }
        for(index in 0 until binding.filters.childCount) (binding.filters.getChildAt(index) as Chip).isChecked=model.query.value.text==values[index]
        binding.filterRow.isVisible=values.isNotEmpty() && catalog.selected!=null && !searchController.visible && !binding.sourcePanel.isVisible && !binding.targetList.isVisible && model.currentSequenceId==null
        syncingQuery=false
    }
    private fun renderEmptySource() {
        val empty = sourcesModel.state.value.loaded && catalog.selected == null
        val configured = sourcesModel.state.value.catalog.profiles.isNotEmpty()
        binding.noSourceTitle.setText(if (configured) R.string.source_floating_empty_title else R.string.source_empty_title)
        binding.noSourceMessage.setText(if (configured) R.string.source_floating_empty_message else R.string.source_empty_message)
        binding.addFirstSource.setText(if (configured) R.string.source_manage else R.string.source_add)
        binding.noSource.isVisible = empty && !binding.sourcePanel.isVisible && !binding.targetList.isVisible && !searchController.visible
        if(empty) { binding.message.isVisible=false; showProgress(false) }
    }
    private fun sourceGestureChoices(): List<SourceGestureChoice> {
        val state = sourcesModel.state.value
        if (!state.loaded || state.saving) return emptyList()
        return catalog.profiles.map { SourceGestureChoice(it.id, it.displayName(this)) }
    }
    private fun cancelPickerGesture(): Boolean {
        val search = searchController.cancelGesture()
        val sources = if (::sourceGesture.isInitialized) sourceGesture.cancel() else false
        return search || sources
    }
    private fun loadTargets() {
        lifecycleScope.launch {
            appChoices = apps.load(options.telegramPackages).filter { it.installed && AppFamilyResolver.resolve(it.packageName, options.telegramPackages) != MessagingTarget.OTHER }
            targetName = appChoices.firstOrNull { it.packageName == targetPackage }?.label
            binding.targetList.adapter = AppChoiceAdapter(appChoices) { app ->
                targetPackage = app.packageName; targetName = app.label; pasteAnchor = null; nativeSession = null
                intent.putExtra(EXTRA_TARGET, targetPackage).removeExtra(EXTRA_ANCHOR)
                intent.removeExtra(EXTRA_NATIVE)
                showTargets(false); refreshCapability(); renderHeader(); renderDock()
            }
            renderHeader(); renderDock()
        }
    }
    private fun refreshCapability() {
        capabilityJob?.cancel(); canShareSticker = false
        val pkg = targetPackage ?: run { canAttachInstagram = false; return }
        val instagram = target == MessagingTarget.INSTAGRAM
        if (!instagram) canAttachInstagram = false
        val mime = carousel.centeredItem?.mimeType ?: "image/jpeg"
        capabilityMime = mime
        capabilityJob = lifecycleScope.launch {
            if (instagram) {
                val supported = withContext(dispatchers.io) { InstagramDelivery.receiver(packageManager, mime) != null ||
                    mime == "video/webm" && InstagramDelivery.receiver(packageManager, "video/mp4") != null }
                if (targetPackage == pkg) { canAttachInstagram = supported; renderDock() }
                return@launch
            }
            val supported = withContext(dispatchers.io) { packageManager.resolveActivity(
                Intent(Intent.ACTION_SEND).setType(TelegramDelivery.DOCUMENT_MIME).setPackage(pkg), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != null }
            if (targetPackage == pkg) { canShareSticker = supported; renderDock() }
        }
    }
    private fun updateCarouselStrategy() {
        val density=resources.displayMetrics.density
        val params=binding.pickerPanel.layoutParams
        carousel.useFullScreen(editing || imeHeight>0 || params.height<360*density || resources.configuration.fontScale>=1.3f)
    }
    private fun resize(imeHeight: Int) {
        if(isFinishing || isDestroyed) return
        this.imeHeight = imeHeight
        val searchPanel = searchController.visible
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val density = resources.displayMetrics.density
        val rawAvailable = maxOf(1, metrics.bounds.height() - bars.top - maxOf(bars.bottom, imeHeight))
        val available = maxOf(1, rawAvailable - (24 * density).toInt())
        val largeText = resources.configuration.fontScale >= 1.3f
        val shortViewport = available < 360 * density
        compactSequence = model.state.value.sequenceId != null && shortViewport && largeText
        val maxWidth = if (shortViewport && largeText) 640 else 400
        val width = minOf((maxWidth * density).toInt(), metrics.bounds.width() - (24 * density).toInt())
        val tight = !searchPanel && imeHeight > 0 && available < 180 * density
        val height = minOf((520 * density).toInt(), if (tight) rawAvailable else available)
        compactEditing = !searchPanel && imeHeight > 0 && available < 300 * density
        binding.pickerHeader.isVisible = !searchPanel
        binding.searchControls.isVisible = editing && searchController.controlsAvailable && !searchPanel && !tight
        binding.dockSurface.isVisible = !searchPanel
        searchController.adapt(imeHeight, available / density)
        binding.filterRow.isVisible = options.showEmojiShortcuts && binding.filters.childCount>0 && catalog.selected!=null && model.state.value.sequenceId == null && !searchPanel && !compactEditing && height >= 320 * density && !(largeText && (shortViewport || imeHeight > 0))
        binding.sequenceBack.isVisible = model.state.value.sequenceId != null && !searchPanel && !binding.sourcePanel.isVisible && !binding.targetList.isVisible
        binding.selectionRow.isVisible = !searchPanel && !compactEditing && !compactSequence
        binding.carouselArea.isVisible = searchPanel || !compactEditing
        updatePlayback()
        updateMessageVisibility()
        val twoRows = largeText && width < 560 * density && !tight
        val mode = if (tight) 2 else if (twoRows) 1 else 0
        if (mode != dockLayoutMode) {
            dockLayoutMode = mode
            binding.pickerContent.orientation = if (tight) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val padding = ((if (tight) 0 else 12) * density).toInt()
            binding.pickerContent.setPadding(padding, padding, padding, padding)
            binding.pickerContent.gravity = if (tight) Gravity.CENTER_VERTICAL else Gravity.TOP
            binding.pickerPanel.radius = (if (tight) 16 else 28) * density
            binding.pickerHeader.layoutParams = LinearLayout.LayoutParams(if (tight) 0 else -1, if (tight) -1 else -2, if (tight) 1f else 0f)
            binding.dockSurface.layoutParams = LinearLayout.LayoutParams(if (tight) (192 * density).toInt() else -1, if (tight) -1 else -2)
            binding.actionDock.orientation = if (twoRows) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            binding.actionDock.gravity = Gravity.CENTER_VERTICAL
            for (i in 0 until binding.actionDock.childCount) binding.actionDock.getChildAt(i).layoutParams =
                LinearLayout.LayoutParams(if (twoRows) -1 else 0, -2, if (twoRows) 0f else 1f)
            binding.searchField.isHintEnabled = !tight
            binding.search.setPadding(binding.search.paddingLeft, ((if (tight) 4 else 16) * density).toInt(),
                binding.search.paddingRight, ((if (tight) 4 else 16) * density).toInt())
            val buttons = listOf(binding.copyAction, binding.pasteAction, binding.stickerAction, binding.mediaAction)
            val labels = listOf(R.string.picker_copy, R.string.picker_paste, R.string.picker_sticker, R.string.picker_media)
            val icons = listOf(R.drawable.ic_picker_copy, R.drawable.ic_picker_paste, R.drawable.ic_picker_sticker, R.drawable.ic_picker_media)
            buttons.forEachIndexed { index, button ->
                button.text = if (tight) "" else getString(labels[index])
                button.icon = if (tight) androidx.appcompat.content.res.AppCompatResources.getDrawable(this, icons[index]) else null
                button.iconPadding = 0; button.contentDescription = getString(labels[index])
                androidx.appcompat.widget.TooltipCompat.setTooltipText(button, if (tight) getString(labels[index]) else null)
            }
        }
        binding.dockDivider.isVisible = !searchPanel && !tight
        showProgress(busy || model.state.value.loading || model.state.value.loadingMore)
        val y = bars.top + if (tight) 0 else (12 * density).toInt() + (available-height)/2
        val params = binding.pickerPanel.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height || params.topMargin != y) binding.pickerPanel.layoutParams = params.apply {
            this.width = width; this.height = height; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = y
        }
        if(!::windowMotion.isInitialized || !windowMotion.animating) updateCarouselStrategy()
        binding.pickerHeader.post { if (!isFinishing && !isDestroyed) renderHeader() }
        updateActionAccessibility()
    }
    private fun showProgress(show: Boolean) {
        binding.progress.visibility=when {
            dockLayoutMode==2 -> View.GONE
            show -> View.VISIBLE
            else -> View.INVISIBLE
        }
    }
    private fun dispatch(action: suspend () -> Unit) {
        if (busy) return
        busy = true; showProgress(true); renderDock()
        lifecycleScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: android.content.ActivityNotFoundException) { inlineFailure(R.string.floating_app_unavailable) }
            catch (error: Exception) { inlineFailure((error as? MediaExportException)?.messageRes ?: R.string.floating_failure) }
            finally { busy = false; showProgress(model.state.value.loading); renderDock() }
        }
    }
    private fun inlineFailure(message: Int) { model.copyStatus.value = message }
    private suspend fun share(item: MediaItem, rendition: ExportRendition) {
        val receivingPackage = targetPackage
        val receivingTarget = target
        if (rendition == ExportRendition.MEDIA && receivingTarget in setOf(MessagingTarget.INSTAGRAM, MessagingTarget.DISCORD) &&
            nativePasteRendition(item) != null) {
            nativeInsert(item, ExportRendition.MEDIA,UsageAction.SHARE)
            return
        }
        var media = exports.prepare(item, rendition, DeliveryPolicy.stickerRequirements(receivingTarget))
        if (item.mimeType == "video/webm" && receivingPackage != null && receivingTarget != MessagingTarget.OTHER) {
            val compatibleMime = withContext(dispatchers.io) {
                VideoCompatibility.mime(item.mimeType) { mime ->
                    if (receivingTarget == MessagingTarget.INSTAGRAM) InstagramDelivery.receiver(packageManager, mime) != null
                    else packageManager.resolveActivity(MediaDelivery.intent(media.copy(mimeType = mime), receivingPackage, item.title),
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != null
                }
            }
            if (compatibleMime == "video/mp4") media = exports.mp4(item)
        }
        if(rendition==ExportRendition.MEDIA) media=limitAttachment(item,media)
        if (receivingTarget == MessagingTarget.INSTAGRAM) {
            val receiver = withContext(dispatchers.io) { InstagramDelivery.receiver(packageManager, media.mimeType) }
            if (receiver == null) {
                canAttachInstagram = false; renderDock(); inlineFailure(R.string.picker_instagram_unavailable)
                return
            }
            startActivity(InstagramDelivery.intent(media, receiver, item.title))
            usage.record(mediaUsage(UsageAction.SHARE,UsageSurface.PICKER,item,receivingTarget.usageTarget(),UsageRoute.ATTACHMENT))
            finish(); return
        }
        if (receivingTarget == MessagingTarget.TELEGRAM && rendition == ExportRendition.STICKER_WEBP) {
            startActivity(TelegramDelivery.stickerIntent(media, requireNotNull(receivingPackage), item.title))
            usage.record(mediaUsage(UsageAction.STICKER,UsageSurface.PICKER,item,receivingTarget.usageTarget(),UsageRoute.ATTACHMENT))
            finish(); return
        }
        val send = MediaDelivery.intent(media, receivingPackage.takeIf { receivingTarget != MessagingTarget.OTHER }, item.title)
        startActivity(if (send.`package` == null) Intent.createChooser(send, getString(R.string.floating_share)) else send)
        usage.record(mediaUsage(UsageAction.SHARE,UsageSurface.PICKER,item,receivingTarget.usageTarget(),UsageRoute.ATTACHMENT))
        finish()
    }
    private suspend fun nativeInsert(item: MediaItem, rendition: ExportRendition = ExportRendition.STICKER_WEBP, action: UsageAction = UsageAction.PASTE) {
        val pkg = targetPackage ?: return; val session = nativeSession ?: return
        var mime = if (rendition == ExportRendition.STICKER_WEBP) "image/webp.wasticker" else nativeMediaMime(item)
        if (!NativeBridgeRegistry.supports(pkg, session, mime)) { inlineFailure(R.string.floating_native_rejected); return }
        val instagram = target == MessagingTarget.INSTAGRAM
        val discord = target == MessagingTarget.DISCORD
        var media = if (item.mimeType == "video/webm" && mime == "video/mp4") exports.mp4(item)
            else exports.prepare(item, rendition, DeliveryPolicy.stickerRequirements(target))
        if(rendition==ExportRendition.MEDIA) {
            media=limitAttachment(item,media)
            mime=media.mimeType
            if(!NativeBridgeRegistry.supports(pkg,session,mime)) { inlineFailure(R.string.floating_native_rejected); return }
        }
        val context = applicationContext
        val event=mediaUsage(action,UsageSurface.PICKER,item,target.usageTarget(),UsageRoute.NATIVE)
        val analytics=usage
        // Explicit handoff must outlive this activity while the original host regains focus.
        deliveryScope.launch {
            val outcome = NativeBridgeRegistry.insert(context, pkg, media.uri, mime, session)
            analytics.record(event.copy(outcome=when(outcome) {
                one.njk.sao.floating.nativebridge.NativeInsertResult.ACCEPTED -> UsageOutcome.ACCEPTED
                one.njk.sao.floating.nativebridge.NativeInsertResult.REJECTED -> UsageOutcome.FAILED
                one.njk.sao.floating.nativebridge.NativeInsertResult.UNKNOWN -> UsageOutcome.UNCERTAIN
            }))
            Toast.makeText(context, when {
                outcome == one.njk.sao.floating.nativebridge.NativeInsertResult.UNKNOWN -> R.string.picker_native_uncertain
                outcome == one.njk.sao.floating.nativebridge.NativeInsertResult.REJECTED -> R.string.floating_native_rejected
                instagram && item.isVideo -> R.string.picker_instagram_native_video_accepted
                instagram -> R.string.picker_instagram_native_accepted
                discord -> R.string.picker_discord_native_accepted
                rendition == ExportRendition.STICKER_WEBP -> R.string.floating_native_accepted
                else -> R.string.picker_native_media_accepted
            }, Toast.LENGTH_LONG).show()
        }
        finish()
    }
    private suspend fun limitAttachment(item: MediaItem, media: ExportedMedia): ExportedMedia {
        val maximum=AttachmentSizePolicy.warningLimit(target) ?: return media
        val bytes=withContext(dispatchers.io) { media.file.length() }
        if(bytes<=maximum) return media
        return when(AttachmentLimitDialog.choose(this,bytes,AttachmentSizePolicy.canReduce(media.mimeType,item.hasMotion))) {
            false -> media
            true -> exports.smaller(item,media,maximum)
            null -> throw CancellationException("Attachment delivery cancelled")
        }
    }
    companion object {
        private const val EXTRA_TARGET = "target_package"
        private const val EXTRA_ANCHOR = "paste_anchor"
        private const val EXTRA_NATIVE = "native_session"
        private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        fun intent(context: Context, packageName: String?, anchor: String?, nativeSession: String? = null) =
            Intent(context, PickerActivity::class.java).putExtra(EXTRA_TARGET, packageName).putExtra(EXTRA_ANCHOR, anchor).putExtra(EXTRA_NATIVE, nativeSession)
    }
}
