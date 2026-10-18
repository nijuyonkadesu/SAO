package one.njk.sao.extension.local

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.source.ExtensionProtocol
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSettingsViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean=false,val working: Boolean=false,val folders: List<Folder> = emptyList(),
        val library: Library?=null,val index: IndexStatus?=null,val folderIndexes: Map<String,IndexStatus> = emptyMap(),
        val indexWork: IndexWork=IndexWork.IDLE,val model: ClipModels.Status=ClipModels.Status(),val useContext: Boolean=false,
        val selectedModel: ClipModel=ClipCatalog.immich,val error: Int?=null,val finished: Boolean=false)
    private val store=LocalStore.get(app)
    private val mutable=MutableStateFlow(State())
    private val selectedModel=MutableStateFlow(ClipCatalog.immich)
    val state=mutable.asStateFlow()
    val editing: String?=saved[ExtensionProtocol.PROFILE]
    val profile=editing ?: UUID.randomUUID().toString()
    var name=app.getString(R.string.default_name)
    init { viewModelScope.launch {
        try {
            val overview=withContext(Dispatchers.IO) {
                try { editing?.let(store::overview) }
                catch(error: one.njk.sao.source.ExtensionFailure) { if(error.code=="PROFILE_MISSING") null else throw error }
            }
            val library=overview?.library
            val selected=ClipCatalog.get(library?.contextModel ?: ClipModels.MODEL); selectedModel.value=selected
            val ready=withContext(Dispatchers.IO) { ClipModels.ready(app,selected) }
            if(library!=null) name=library.name
            mutable.value=State(loaded=true,folders=library?.folders.orEmpty(),library=library,
                index=overview?.total,folderIndexes=overview?.folders.orEmpty(),model=ClipModels.Status(ready=ready),useContext=library?.contextSearch ?: ready,selectedModel=selected)
            mutable.subscriptionCount.map { it>0 }.distinctUntilChanged()
                .flatMapLatest { if(it) combine(store.changes,FolderIndexer.observe(app,profile),selectedModel.flatMapLatest { selected -> ClipModels.observe(app,selected).map { selected to it } }) { _,work,model -> work to model } else emptyFlow() }.collectLatest { (work,pair) ->
                val (selected,model)=pair
                val updated=if(mutable.value.library!=null) withContext(Dispatchers.IO) { store.overview(profile) } else null
                mutable.update { before -> before.copy(library=updated?.library,index=updated?.total,folderIndexes=updated?.folders.orEmpty(),indexWork=work,
                    model=model,selectedModel=selected,useContext=before.useContext || !before.model.ready && model.ready) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(loaded=true,error=R.string.storage_error) } }
    } }
    fun folder(uri: Uri) { viewModelScope.launch {
        try {
            require(DocumentsContract.isTreeUri(uri))
            val title=withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }
                    ?: throw java.io.IOException("Folder unavailable")
            }
            mutable.update { before -> before.copy(folders=if(before.folders.any { it.uri==uri.toString() }) before.folders else before.folders+Folder(uri.toString(),title),error=null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(error=R.string.folder_error) } }
    } }
    fun rename(folder: Folder, name: String) { if(name.isNotBlank()) mutable.update { before -> before.copy(folders=before.folders.map { if(it.uri==folder.uri) it.copy(name=name.trim()) else it }) } }
    fun remove(folder: Folder) { mutable.update { before -> before.copy(folders=before.folders.filterNot { it.uri==folder.uri }) } }
    fun useContext(enabled: Boolean) { mutable.update { it.copy(useContext=enabled && it.model.ready) } }
    fun selectModel(index: Int) {
        if(state.value.working) return
        val selected=ClipCatalog.all[index]
        if(selected==state.value.selectedModel) return
        mutable.update { it.copy(selectedModel=selected,model=ClipModels.Status(),useContext=false) }
        selectedModel.value=selected
    }
    fun modelFolder(uri: Uri) {
        val selected=state.value.selectedModel
        action { ClipModels.select(getApplication(),selected,uri); releaseUnusedGrants(getApplication(),store) }
    }
    fun downloadModel() { val selected=state.value.selectedModel; action { ClipModels.download(getApplication(),selected) } }
    fun pauseDownload() { val selected=state.value.selectedModel; action { ClipModels.cancel(getApplication(),selected) } }
    private fun action(block: suspend () -> Unit) {
        if(!state.value.loaded || state.value.working) return
        mutable.update { it.copy(working=true,error=null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error=R.string.storage_error) } }
            finally { mutable.update { it.copy(working=false) } }
        }
    }
    fun save() {
        if(name.isBlank() || state.value.folders.isEmpty()) { mutable.update { it.copy(error=R.string.configuration_error) }; return }
        val folders=state.value.folders.toList(); val title=name; val useContext=state.value.useContext; val selected=state.value.selectedModel
        action {
            val app=getApplication<Application>(); val resolver=app.contentResolver
            try {
                folders.forEach { resolver.takePersistableUriPermission(Uri.parse(it.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val prior=state.value.library
                val library=store.save(profile,title,folders,contextSearch=useContext && ClipModels.ready(app,selected),contextModel=selected.id)
                if(prior!=null && prior.revision!=library.revision) {
                    app.revokeUriPermission(mediaScope(app,prior),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.revokeUriPermission(store.changesUri(prior),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                releaseUnusedGrants(app,store)
                val foldersChanged=prior?.folders?.associate { it.uri to it.name }!=library.folders.associate { it.uri to it.name }
                FolderIndexer.schedule(app,library,force=foldersChanged || prior?.contextSearch!=library.contextSearch || prior?.contextModel!=library.contextModel,
                    reset=prior?.folders?.map { it.uri }!=library.folders.map { it.uri })
                mutable.update { it.copy(library=library,finished=true) }
            } catch (error: Exception) { releaseUnusedGrants(app,store); throw error }
        }
    }
    fun rescan()=action {
        val app=getApplication<Application>(); val library=store.library(profile)
        store.pause(profile,false); store.retryFailures(profile)
        FolderIndexer.schedule(app,library.copy(paused=false),force=true)
    }
    fun pause()=action {
        val app=getApplication<Application>(); val library=store.library(profile)
        store.pause(profile,!library.paused)
        if(library.paused) FolderIndexer.schedule(app,library.copy(paused=false),force=true,reset=false) else FolderIndexer.cancel(app,profile)
    }
    fun pauseFolder(folder: Folder)=action {
        val app=getApplication<Application>()
        FolderIndexer.change(app,profile) {
            val paused=store.library(profile).folders.single { it.uri==folder.uri }.paused
            store.pauseFolder(profile,folder.uri,!paused)
        }
        FolderIndexer.schedule(app,store.library(profile),force=true,reset=false)
    }
    fun restartFolder(folder: Folder)=action {
        val app=getApplication<Application>()
        FolderIndexer.change(app,profile) { store.restartFolder(profile,folder.uri) }
        FolderIndexer.schedule(app,store.library(profile),force=true,reset=false)
    }
    fun delete()=action {
        val app=getApplication<Application>(); val library=store.library(profile)
        FolderIndexer.cancel(app,profile); store.delete(profile); releaseUnusedGrants(app,store)
        app.revokeUriPermission(mediaScope(app,library),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.revokeUriPermission(store.changesUri(library),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        mutable.update { it.copy(finished=true) }
    }
}
