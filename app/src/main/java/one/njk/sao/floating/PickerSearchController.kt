package one.njk.sao.floating

import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil3.ImageLoader
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.ActivityPickerBinding

/** Native inline search controls; only the active section is shown. Delivery remains in the dock. */
internal class PickerSearchController(private val activity: AppCompatActivity, private val binding: ActivityPickerBinding,
    private val picker: PickerViewModel, private val options: SearchOptionsViewModel,
    source: MediaSource, loader: ImageLoader, private val visibilityChanged: (Boolean) -> Unit,
    private val redraw: () -> Unit, private val randomRequested: () -> Unit, private val beforeGesture: () -> Unit = {}) {
    private val panel = binding.searchOptionsPanel
    private var updating = false
    private var section = R.id.section_type
    private var selectedPeople: List<SearchPerson> = emptyList()
    private var countryChoices = emptyList<String?>()
    private var stateChoices = emptyList<String?>()
    private var cityChoices = emptyList<String?>()
    private val people = SearchPeopleAdapter(source, loader, activity.lifecycleScope, ::choosePerson)
    val visible get() = panel.root.isVisible
    val supported get() = options.state.value.supported
    val controlsAvailable get() = supported || options.state.value.randomSupported
    private val constraints get() = picker.query.value.constraints.forSource(options.state.value.sourceId)
    private val modes = mapOf(R.id.mode_context to SearchMode.CONTEXT, R.id.mode_filename to SearchMode.FILENAME,
        R.id.mode_description to SearchMode.DESCRIPTION, R.id.mode_path to SearchMode.PATH, R.id.mode_ocr to SearchMode.OCR)
    private enum class QuickFilter { ALL_MEDIA, IMAGES, VIDEOS, PEOPLE, PLACES, RESET }
    private val modeGesture: HoldDragSelector<SearchMode> = HoldDragSelector(binding.queryMode, binding.root as android.widget.FrameLayout,
        choices = { if (supported) SearchMode.entries.filter { it in options.state.value.modes } else emptyList() },
        selected = { picker.query.value.mode }, label = { activity.getString(modeLabel(it)) },
        identity = { options.state.value.sourceId }, apply = { picker.searchMode(it) }, opening = { filterGesture.cancel(); beforeGesture() })
    private val filterGesture: HoldDragSelector<QuickFilter> = HoldDragSelector(binding.searchFilters, binding.root as android.widget.FrameLayout,
        choices = { if (supported) QuickFilter.entries.toList() else emptyList() },
        selected = { when (constraints.media) {
            SearchMedia.ALL -> QuickFilter.ALL_MEDIA; SearchMedia.IMAGES -> QuickFilter.IMAGES; SearchMedia.VIDEOS -> QuickFilter.VIDEOS
        } },
        label = { activity.getString(when (it) {
            QuickFilter.ALL_MEDIA -> R.string.search_media_all; QuickFilter.IMAGES -> R.string.search_media_images
            QuickFilter.VIDEOS -> R.string.search_media_videos; QuickFilter.PEOPLE -> R.string.search_hold_people
            QuickFilter.PLACES -> R.string.search_hold_places; QuickFilter.RESET -> R.string.search_reset
        }) }, identity = { options.state.value.sourceId }, apply = ::applyQuickFilter,
        title = R.string.search_filters, chooseAction = R.string.search_hold_edit_filters,
        nextAction = R.string.search_hold_next_filter, previousAction = R.string.search_hold_previous_filter,
        opening = { modeGesture.cancel(); beforeGesture() }, hint = { activity.getString(when (it) {
            QuickFilter.PEOPLE -> R.string.search_hold_choose_people; QuickFilter.PLACES -> R.string.search_hold_choose_places
            else -> R.string.search_hold_release
        }) })
    init {
        binding.queryMode.setOnClickListener { open(R.id.section_type) }
        binding.searchFilters.setOnClickListener { open(R.id.section_people) }
        binding.clearSearchFilters.setOnClickListener { picker.clearSearchConstraints() }
        binding.searchLucky.setOnClickListener { randomRequested() }
        panel.optionsLucky.setOnClickListener { randomRequested() }
        androidx.appcompat.widget.TooltipCompat.setTooltipText(binding.searchLucky, activity.getString(R.string.search_lucky))
        panel.optionsBack.setOnClickListener { close() }
        panel.optionsReset.setOnClickListener { picker.clearSearchConstraints() }
        panel.typeRetry.setOnClickListener { options.modes(retry = true) }
        panel.optionsSections.setOnCheckedStateChangeListener { _, ids -> if (!updating) showSection(ids.firstOrNull() ?: R.id.section_type) }
        // Clicking the already checked mode also exits random browsing.
        modes.forEach { (id, mode) -> panel.root.findViewById<Chip>(id).setOnClickListener {
            if (!updating) { picker.searchMode(mode); close() }
        } }
        panel.mediaChoices.setOnCheckedStateChangeListener { _, ids -> if (!updating) {
            update(constraints.copy(media = when (ids.firstOrNull()) { R.id.media_images -> SearchMedia.IMAGES; R.id.media_videos -> SearchMedia.VIDEOS; else -> SearchMedia.ALL }))
        } }
        panel.peopleList.layoutManager = LinearLayoutManager(activity)
        panel.peopleList.adapter = people
        panel.peopleName.doAfterTextChanged { if (!updating && visible && section == R.id.section_people) options.people(it?.toString().orEmpty()) }
        panel.peopleName.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        panel.peopleMore.setOnClickListener {
            if (options.state.value.peopleError) options.people(retry = true) else options.people(more = true)
        }
        panel.country.setOnItemClickListener { _, _, index, _ -> update(constraints.copy(country = countryChoices.getOrNull(index), state = null, city = null)) }
        panel.state.setOnItemClickListener { _, _, index, _ -> update(constraints.copy(state = stateChoices.getOrNull(index), city = null)) }
        panel.city.setOnItemClickListener { _, _, index, _ -> update(constraints.copy(city = cityChoices.getOrNull(index))) }
        panel.placesRetry.setOnClickListener { options.places(constraints.country, constraints.state, retry = true) }
        activity.lifecycleScope.launch { activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            options.state.collect { state ->
                modeGesture.update()
                filterGesture.update()
                updating = true
                if (panel.peopleName.text.toString() != state.peopleName) panel.peopleName.setText(state.peopleName)
                people.submitList(state.people)
                modes.forEach { (id, mode) -> panel.root.findViewById<Chip>(id).isEnabled = mode in state.modes }
                panel.typeStatus.isVisible = state.supported && (state.modesError || SearchMode.CONTEXT !in state.modes)
                panel.typeStatus.setText(if (state.modesError) R.string.search_options_error else R.string.search_context_unavailable)
                panel.typeRetry.isVisible = state.supported && state.modesError
                binding.queryMode.isVisible = state.supported
                binding.searchFilters.isVisible = state.supported
                binding.searchLucky.isVisible = state.randomSupported
                panel.searchModes.isVisible = state.supported
                panel.typeDescription.isVisible = state.supported
                panel.optionsLucky.isVisible = state.randomSupported
                panel.luckyDescription.isVisible = state.randomSupported
                panel.optionsReset.isVisible = state.supported
                panel.peopleStatus.setText(when {
                    state.peopleError -> R.string.search_options_error
                    state.peopleLoading -> R.string.search_people_loading
                    state.people.isEmpty() -> R.string.search_people_empty
                    else -> R.string.search_people
                })
                panel.peopleStatus.isVisible = state.peopleLoading || state.peopleError || state.people.isEmpty()
                panel.peopleMore.isVisible = state.peopleError || state.nextPeoplePage != null
                panel.peopleMore.isEnabled = !state.peopleLoading
                panel.peopleMore.setText(if (state.peopleError) R.string.search_retry else R.string.search_more_people)
                setPlaces(state)
                updating = false
                if (!controlsAvailable && visible) close()
                else if (!state.supported && visible) showSection(R.id.section_type)
                renderQuery(); redraw()
            }
        } }
    }
    private fun update(value: SearchConstraints) {
        picker.searchConstraints(value.copy(sourceId = options.state.value.sourceId))
        if (visible && section == R.id.section_places) options.places(value.country, value.state)
    }
    private fun applyQuickFilter(value: QuickFilter) {
        when (value) {
            QuickFilter.ALL_MEDIA -> update(constraints.copy(media = SearchMedia.ALL))
            QuickFilter.IMAGES -> update(constraints.copy(media = SearchMedia.IMAGES))
            QuickFilter.VIDEOS -> update(constraints.copy(media = SearchMedia.VIDEOS))
            QuickFilter.PEOPLE -> open(R.id.section_people)
            QuickFilter.PLACES -> open(R.id.section_places)
            QuickFilter.RESET -> picker.clearSearchConstraints()
        }
    }
    private fun choosePerson(person: SearchPerson) {
        if (person.sourceId != options.state.value.sourceId) return
        val current = constraints.people
        update(constraints.copy(people = if (current.any { it.id == person.id }) current.filterNot { it.id == person.id } else current + person))
    }
    fun open(section: Int = R.id.section_type) {
        cancelGesture()
        if (!controlsAvailable) return
        options.modes()
        panel.root.isVisible = true; visibilityChanged(true)
        showSection(if (supported) section else R.id.section_type); renderQuery(); hideKeyboard()
    }
    fun close() {
        cancelGesture()
        if (!visible) return
        panel.root.isVisible = false; panel.peopleName.clearFocus(); visibilityChanged(false)
    }
    private fun showSection(value: Int) {
        section = value
        updating = true; panel.optionsSections.check(value); updating = false
        panel.typePanel.isVisible = value == R.id.section_type
        panel.peoplePanel.isVisible = value == R.id.section_people
        panel.placesPanel.isVisible = value == R.id.section_places
        panel.mediaPanel.isVisible = value == R.id.section_media
        if (value == R.id.section_people) options.people()
        if (value == R.id.section_places) options.places(constraints.country, constraints.state)
    }
    fun renderQuery() {
        val query = picker.query.value
        val criteria = constraints
        val label = activity.getString(if (query.randomRequest != null) R.string.search_random_mode else modeLabel(query.mode))
        if (binding.queryMode.text.toString() != label) binding.queryMode.text = label
        binding.queryMode.contentDescription = activity.getString(R.string.search_hold_accessibility, label)
        val filtersLabel = if (criteria.count == 0) activity.getString(R.string.search_filters)
            else activity.getString(R.string.search_filters_count, criteria.count)
        if (binding.searchFilters.text.toString() != filtersLabel) binding.searchFilters.text = filtersLabel
        binding.searchFilters.contentDescription = activity.getString(R.string.search_hold_filters_accessibility, criteria.count)
        binding.clearSearchFilters.isVisible = supported && criteria.count > 0
        val random = query.randomRequest != null
        val luckyLabel = if (random) R.string.search_lucky_shuffle else R.string.search_lucky_short
        if (binding.searchLucky.text.toString() != activity.getString(luckyLabel)) binding.searchLucky.setText(luckyLabel)
        binding.searchLucky.contentDescription = activity.getString(if (random) R.string.search_lucky_again else R.string.search_lucky)
        panel.optionsLucky.setText(if (random) R.string.search_lucky_again else R.string.search_lucky)
        renderLoading(picker.state.value)
        binding.searchField.hint = activity.getString(if (random) R.string.search_hint_random else if (!supported) R.string.floating_search_hint else when (query.mode) {
            SearchMode.CONTEXT -> R.string.floating_search_hint; SearchMode.FILENAME -> R.string.search_hint_filename
            SearchMode.DESCRIPTION -> R.string.search_hint_description; SearchMode.PATH -> R.string.search_hint_path; SearchMode.OCR -> R.string.search_hint_ocr
        })
        updating = true
        panel.searchModes.check(modes.entries.first { it.value == query.mode }.key)
        panel.mediaChoices.check(when (criteria.media) { SearchMedia.ALL -> R.id.media_all; SearchMedia.IMAGES -> R.id.media_images; SearchMedia.VIDEOS -> R.id.media_videos })
        val any = activity.getString(R.string.search_any)
        listOf(panel.country to criteria.country, panel.state to criteria.state, panel.city to criteria.city).forEach { (view, value) ->
            if (view.text.toString() != (value ?: any)) view.setText(value ?: any, false)
        }
        people.selected = criteria.people.map { it.id }.toSet()
        if (selectedPeople != criteria.people) {
            selectedPeople = criteria.people
            panel.selectedPeople.removeAllViews()
            criteria.people.forEach { person -> panel.selectedPeople.addView(Chip(activity).apply {
                text = person.name.ifBlank { activity.getString(R.string.search_person_unnamed) }
                isCloseIconVisible = true
                closeIconContentDescription = activity.getString(R.string.search_person_remove, text)
                setOnCloseIconClickListener { update(constraints.copy(people = constraints.people.filterNot { it.id == person.id })) }
            }) }
        }
        panel.selectedPeopleRow.isVisible = criteria.people.isNotEmpty()
        updating = false
    }
    fun renderLoading(state: PickerState) {
        val enabled = options.state.value.randomSupported && !state.loading && !state.loadingMore && state.query == picker.query.value
        binding.searchLucky.isEnabled = enabled
        panel.optionsLucky.isEnabled = enabled
    }
    private fun setPlaces(state: SearchOptionsState) {
        fun choices(view: com.google.android.material.textfield.MaterialAutoCompleteTextView, old: List<String?>, values: List<String>): List<String?> {
            val next = listOf(null) + values
            if (old != next) view.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, next.map { it ?: activity.getString(R.string.search_any) }))
            return next
        }
        countryChoices = choices(panel.country, countryChoices, state.countries)
        stateChoices = choices(panel.state, stateChoices, state.states)
        cityChoices = choices(panel.city, cityChoices, state.cities)
        panel.placesStatus.setText(if (state.placesError) R.string.search_options_error else R.string.search_places_loading)
        panel.placesStatus.isVisible = state.placesError || state.placesLoading
        panel.placesRetry.isVisible = state.placesError
    }
    fun adapt(imeHeight: Int, availableDp: Float) {
        panel.optionsTabs.isVisible = supported && (imeHeight == 0 || availableDp >= 250)
        panel.optionsHeader.isVisible = imeHeight == 0 || availableDp >= 150
        panel.peopleNameField.isHintEnabled = availableDp >= 150
    }
    fun hideKeyboard() = WindowCompat.getInsetsController(activity.window, panel.root).hide(WindowInsetsCompat.Type.ime())
    fun loadModes() = options.modes()
    fun cancelGesture(): Boolean {
        val mode = modeGesture.cancel(); val filters = filterGesture.cancel()
        return mode || filters
    }
    fun dispose() { modeGesture.dispose(); filterGesture.dispose(); people.close(); panel.peopleList.adapter = null }
    companion object {
        fun modeLabel(mode: SearchMode) = when (mode) {
            SearchMode.CONTEXT -> R.string.search_mode_context; SearchMode.FILENAME -> R.string.search_mode_filename
            SearchMode.DESCRIPTION -> R.string.search_mode_description; SearchMode.PATH -> R.string.search_mode_path; SearchMode.OCR -> R.string.search_mode_ocr
        }
    }
}
