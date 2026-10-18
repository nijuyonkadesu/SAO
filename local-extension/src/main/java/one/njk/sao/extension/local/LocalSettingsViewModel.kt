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
        val library: Library?=null,val index: IndexStatus?=null,val error: Int?=null,val finished: Boolean=false)
    private val store=LocalStore.get(app)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    val editing: String?=saved[ExtensionProtocol.PROFILE]
    val profile=editing ?: UUID.randomUUID().toString()
    var name=app.getString(R.string.default_name)
    init { viewModelScope.launch {
        try {
            val library=withContext(Dispatchers.IO) { editing?.let(store::library) }
            if(library!=null) name=library.name
            mutable.value=State(loaded=true,folders=library?.folders.orEmpty(),library=library,
                index=withContext(Dispatchers.IO) { library?.let { store.status(it.id) } })
            mutable.subscriptionCount.map { it>0 }.distinctUntilChanged()
                .flatMapLatest { if(it) store.changes else emptyFlow() }.collectLatest {
                if(mutable.value.library!=null) {
                    val updated=withContext(Dispatchers.IO) { store.library(profile) to store.status(profile) }
                    mutable.value=mutable.value.copy(library=updated.first,index=updated.second)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value=mutable.value.copy(loaded=true,error=R.string.storage_error) }
    } }
    fun folder(uri: Uri) { viewModelScope.launch {
        try {
            require(DocumentsContract.isTreeUri(uri))
            val title=withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null }
                    ?: throw java.io.IOException("Folder unavailable")
            }
            val folders=state.value.folders
            mutable.value=state.value.copy(folders=if(folders.any { it.uri==uri.toString() }) folders else folders+Folder(uri.toString(),title),error=null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value=state.value.copy(error=R.string.folder_error) }
    } }
    fun rename(folder: Folder, name: String) { if(name.isNotBlank()) mutable.value=state.value.copy(folders=state.value.folders.map { if(it.uri==folder.uri) it.copy(name=name.trim()) else it }) }
    fun remove(folder: Folder) { mutable.value=state.value.copy(folders=state.value.folders.filterNot { it.uri==folder.uri }) }
    private fun action(block: suspend () -> Unit) {
        if(!state.value.loaded || state.value.working) return
        mutable.value=state.value.copy(working=true,error=null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value=state.value.copy(error=R.string.storage_error) }
            finally { mutable.value=state.value.copy(working=false) }
        }
    }
    fun save() {
        if(name.isBlank() || state.value.folders.isEmpty()) { mutable.value=state.value.copy(error=R.string.configuration_error); return }
        val folders=state.value.folders.toList(); val title=name
        action {
            val app=getApplication<Application>(); val resolver=app.contentResolver
            try {
                folders.forEach { resolver.takePersistableUriPermission(Uri.parse(it.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val prior=state.value.library
                val library=store.save(profile,title,folders)
                if(prior!=null && prior.revision!=library.revision) {
                    app.revokeUriPermission(mediaScope(app,prior),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.revokeUriPermission(store.changesUri(prior),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                releaseUnusedGrants(app,store)
                FolderIndexer.schedule(app,library,force=prior?.folders!=library.folders)
                mutable.value=state.value.copy(library=library,finished=true)
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
        if(library.paused) FolderIndexer.schedule(app,library.copy(paused=false),force=true) else FolderIndexer.cancel(app,profile)
    }
    fun delete()=action {
        val app=getApplication<Application>(); val library=store.library(profile)
        FolderIndexer.cancel(app,profile); store.delete(profile); releaseUnusedGrants(app,store)
        app.revokeUriPermission(mediaScope(app,library),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.revokeUriPermission(store.changesUri(library),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        mutable.value=state.value.copy(finished=true)
    }
}
