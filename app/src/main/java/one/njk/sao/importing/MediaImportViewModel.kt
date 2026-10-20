package one.njk.sao.importing

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.source.ImportTarget
import one.njk.sao.source.SourceProfile
import javax.inject.Inject

@HiltViewModel
class MediaImportViewModel @Inject constructor(internal val repository: MediaImports,private val saved: SavedStateHandle): ViewModel() {
    internal data class State(val loaded: Boolean=false,val working: Boolean=false,val destinations: List<MediaImports.Destination> = emptyList(),
        val profile: SourceProfile?=null,val selected: MediaImports.Destination?=null,val target: ImportTarget?=null,
        val routes: List<ImportStore.Route> = emptyList(),val stats: ImportStore.Stats=ImportStore.Stats(),
        val error: String?=null,val prepared: Int=0,val total: Int=0,val received: Boolean=false,
        val incomingTypes: Set<String> = emptySet(),val inspecting: Boolean=false,val preparing: Boolean=false)
    private val mutable=MutableStateFlow(State())
    internal val state=mutable.asStateFlow()
    private var loading: Job?=null
    private var observation: Job?=null
    init { refresh() }
    fun incoming(uris: List<Uri>) {
        if(saved.get<Boolean>("received")==true) return
        saved["incoming"]=ArrayList(uris.distinct()); mutable.update { it.copy(total=uris.size,inspecting=uris.isNotEmpty()) }
        viewModelScope.launch {
            try {
                val types=repository.types(uris)
                mutable.update { it.copy(incomingTypes=types,inspecting=false) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(inspecting=false,error=MediaImports.code(error)) } }
        }
    }
    fun refresh() {
        loading?.cancel()
        loading=viewModelScope.launch {
            try {
                val id=saved.get<String>("source")
                val profile=id?.let { repository.source(it) }
                mutable.update { it.copy(profile=profile) }
                val destinations=repository.destinations(id)
                val selected=destinations.firstOrNull { it.source.id==id }
                val last=withContext(Dispatchers.IO) { id?.let { repository.store.lastTarget(it) } }
                val target=selected?.options?.targets?.firstOrNull { it.id==(saved.get<String>("target") ?: last) }
                    ?: selected?.options?.targets?.firstOrNull()
                mutable.update { it.copy(loaded=true,profile=profile,destinations=destinations,selected=selected,target=target,error=null) }
                observe(id)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(loaded=true,error=MediaImports.code(error)) } }
        }
    }
    private fun observe(id: String?) {
        observation?.cancel(); if(id==null) return
        observation=viewModelScope.launch {
            repository.store.changes.mapLatest { withContext(Dispatchers.IO) { repository.store.routes(id) to repository.store.stats(id) } }
                .collect { (routes,stats) -> mutable.update { it.copy(routes=routes,stats=stats) } }
        }
    }
    fun select(destination: MediaImports.Destination) {
        saved["source"]=destination.source.id
        mutable.update { it.copy(profile=destination.source,selected=destination,target=destination.options.targets.firstOrNull(),error=null) }
        observe(destination.source.id)
        if(destination.options.targets.size==1 && !saved.get<ArrayList<Uri>>("incoming").isNullOrEmpty()) receive()
    }
    fun target(target: ImportTarget) {
        saved["target"]=target.id; mutable.update { it.copy(target=target) }
        if(!saved.get<ArrayList<Uri>>("incoming").isNullOrEmpty()) receive()
    }
    fun receive(uris: List<Uri>?=null) {
        val destination=state.value.selected ?: return
        val target=state.value.target ?: return
        val input=uris ?: saved.get<ArrayList<Uri>>("incoming").orEmpty()
        if(input.isEmpty()) return
        action {
            mutable.update { it.copy(prepared=0,total=input.size,preparing=true) }
            try { repository.enqueue(destination,target,input) { done,total -> mutable.update { it.copy(prepared=done,total=total) } } }
            finally { saved["incoming"]=null; saved["received"]=true }
            mutable.update { it.copy(received=true) }
        }
    }
    fun connect(uri: Uri) {
        val selected=state.value.selected ?: return; val target=state.value.target ?: return
        action { repository.connect(selected,target,uri) }
    }
    internal fun disconnect(route: ImportStore.Route)=action { repository.remove(route) }
    fun pause()=action {
        val profile=state.value.profile ?: return@action
        repository.pause(profile.id)
    }
    fun retry()=action {
        val profile=state.value.profile ?: return@action
        repository.retry(profile.id)
    }
    fun check()=action {
        val profile=state.value.profile ?: return@action
        repository.check(profile.id)
    }
    fun clear()=action { state.value.profile?.let { repository.clear(it.id) } }
    private fun action(block: suspend () -> Unit) {
        if(state.value.working) return
        mutable.update { it.copy(working=true,error=null) }
        viewModelScope.launch {
            try { block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(error=MediaImports.code(error)) } }
            finally { mutable.update { it.copy(working=false,preparing=false) } }
        }
    }
}
