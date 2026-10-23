package one.njk.sao.floating

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.R
import one.njk.sao.floating.search.SourceCompletionContext
import one.njk.sao.floating.search.SuggestionRequests
import one.njk.sao.source.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider

data class HomeCategoryDraft(val category: SourceCategory)
data class HomeCategoriesState(val profile: SourceProfile?=null,val categories: List<SourceCategory> = emptyList(),
    val capabilities: SourceCapabilities=SourceCapabilities(),val loaded: Boolean=false,val saving: Boolean=false,
    val customized: Boolean=false,val error: Int?=null,val draft: HomeCategoryDraft?=null)

/** Editing never changes the active browsing profile or writes draft keystrokes to storage. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCategoriesViewModel @Inject constructor(private val profiles: SourceProfiles,
    private val factories: Map<String,@JvmSuppressWildcards Provider<MediaSourceFactory>>,
    private val dispatchers: FloatingDispatchers,private val saved: SavedStateHandle,
    private val suggestionRequests: SuggestionRequests) : ViewModel() {
    private val catalog=MutableStateFlow(SourceCatalog())
    private val chosen=MutableStateFlow(saved.get<String>("home_profile"))
    private val retry=MutableStateFlow(0)
    private val mutable=MutableStateFlow(HomeCategoriesState()); val state=mutable.asStateFlow()
    private var source: ManagedMediaSource?=null
    init { viewModelScope.launch {
        combine(catalog,chosen,retry) { available,id,_ -> available.profiles.firstOrNull { it.id==id }
            ?: available.selected ?: available.profiles.firstOrNull() }.distinctUntilChangedBy { it?.identity to retry.value }.collectLatest { profile ->
            val previous=state.value
            mutable.value=HomeCategoriesState(profile=profile,draft=previous.draft.takeIf { previous.profile?.id==profile?.id })
            if(profile==null) return@collectLatest
            val client=if(profile.configuration is AllSourceConfiguration) AllMediaSource(profile,factories,dispatchers)
                else factories[profile.configuration.kind]?.get()?.create(profile)
            if(client==null) { mutable.value=state.value.copy(error=R.string.source_extension_missing); return@collectLatest }
            source=client
            try {
                combine(client.capabilitiesState,profiles.snapshots) { status,snapshot ->
                    val custom=snapshot.homeCategories[profile.id]
                    when (status) {
                        SourceCapabilitiesState.Loading -> state.value.copy(loaded=false,error=null)
                        is SourceCapabilitiesState.Failed -> state.value.copy(loaded=false,
                            error=(status.error as? SourceReadException)?.messageRes ?: R.string.source_extension_failed)
                        is SourceCapabilitiesState.Ready -> state.value.copy(profile=profile,capabilities=status.value,
                            loaded=true,categories=custom ?: status.value.categories,customized=custom!=null,error=null)
                    }
                }.collect { mutable.value=it }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.value=state.value.copy(error=(error as? SourceReadException)?.messageRes ?: R.string.source_extension_failed) }
            finally { client.close(); if(source===client) source=null }
        }
    } }
    fun catalog(value: SourceCatalog) {
        if(chosen.value==null) { chosen.value=value.selectedId ?: value.profiles.firstOrNull()?.id; saved["home_profile"]=chosen.value }
        catalog.value=value
    }
    fun select(id: String) {
        if(state.value.saving || state.value.draft!=null) return
        saved["home_profile"]=id; chosen.value=id
    }
    fun retry() { retry.value++ }
    fun edit(category: SourceCategory?=null) {
        val current=state.value
        if(!current.loaded || current.saving || current.capabilities.modes.isEmpty()) return
        mutable.value=current.copy(draft=HomeCategoryDraft(category ?: SourceCategory(UUID.randomUUID().toString(),"",
            MediaQuery(mode=current.capabilities.defaultMode))))
    }
    fun label(value: String) { draft { it.copy(label=value) } }
    fun query(value: String) { draft { it.copy(query=it.query.copy(text=value)) } }
    fun mode(value: SearchMode) { draft { it.copy(query=it.query.copy(mode=value)) } }
    private fun draft(change: (SourceCategory)->SourceCategory) {
        if(!state.value.saving) state.value.draft?.let { mutable.value=state.value.copy(draft=HomeCategoryDraft(change(it.category))) }
    }
    fun cancel() { if(!state.value.saving) mutable.value=state.value.copy(draft=null) }
    fun save() {
        val category=state.value.draft?.category ?: return
        val query=state.value.capabilities.normalize(category.query.copy(text=category.query.text.trim()))
        val label=category.label.trim().ifEmpty { query.text.replace('_',' ').ifEmpty { "All" }.take(80) }
        val row=category.copy(label=label,query=query)
        val rows=state.value.categories.toMutableList()
        val index=rows.indexOfFirst { it.id==row.id }
        if(index<0) rows+=row else rows[index]=row
        persist(rows)
    }
    fun remove(id: String) = persist(state.value.categories.filterNot { it.id==id })
    fun reset() = persist(null)
    private fun persist(rows: List<SourceCategory>?) {
        val profile=state.value.profile ?: return
        if(!state.value.loaded || state.value.saving) return
        mutable.value=state.value.copy(saving=true,error=null)
        viewModelScope.launch {
            try { profiles.homeCategories(profile.id,rows); mutable.value=state.value.copy(saving=false,draft=null) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.value=state.value.copy(saving=false,error=R.string.source_setup_storage_error) }
        }
    }
    suspend fun suggestions(context: SourceCompletionContext,cursor: Int): SearchSuggestions {
        check(state.value.profile?.identity==context.sourceId)
        val client=checkNotNull(source)
        return suggestionRequests.run(context.sourceId,cursor) {
            client.withSource(context.sourceId) { it.suggestions(context.query,cursor) }
                .also { check(state.value.profile?.identity==context.sourceId) }
        }
    }
}
