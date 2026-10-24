package one.njk.sao.extensions

import android.content.pm.PackageInstaller
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import one.njk.sao.R
import javax.inject.Inject

internal enum class InstallPhase { DOWNLOADING, READY, INSTALLING }
internal data class CatalogState(val entries: List<CatalogExtension> = emptyList(),val loading: Boolean = false,
    val error: Int? = null,val activePackage: String? = null,val phase: InstallPhase? = null,val progress: Int = 0,
    val failures: Map<String,Int> = emptyMap(),val inventoryRevision: Int = 0,val session: Int? = null,
    val repositories: List<String> = emptyList(),val repositoriesLoaded: Boolean=false,
    val repositoryErrors: Map<String,Int> = emptyMap(),val repositorySaveError: Boolean=false)

@HiltViewModel
class ExtensionCatalogViewModel @Inject constructor(private val repository: CatalogRepository,
    private val installer: ExtensionInstaller,private val events: ExtensionInstallEvents,private val saved: SavedStateHandle,
    private val repositories: ExtensionRepositories): ViewModel() {
    private val mutable=MutableStateFlow(CatalogState())
    internal val state=mutable.asStateFlow()
    internal val installEvents=events.state
    private var loadJob: Job?=null
    private var transfer: Job?=null
    private var ready: VerifiedExtension?=null
    private var downloading: CatalogExtension?=null
    private val repositoryChanges=Mutex()
    private val loadedCatalogs=mutableMapOf<String,List<CatalogExtension>>()
    private val attemptedRepositories=mutableSetOf<String>()
    private var permissionGranted=false
    private val requests=java.util.concurrent.atomic.AtomicLong()
    init {
        viewModelScope.launch {
            try { repositories.urls.collect { urls ->
                loadJob?.cancelAndJoin()
                // Catalog priority may change while a transfer is running; use its actual origin.
                val active=ready?.entry ?: downloading
                if(active!=null && active.repositoryUrl !in urls && mutable.value.phase!=InstallPhase.INSTALLING)
                    this@ExtensionCatalogViewModel.cancel()
                loadedCatalogs.keys.retainAll(urls.toSet()); attemptedRepositories.retainAll(urls.toSet())
                val pending=urls.filter { it !in attemptedRepositories }
                mutable.update { it.copy(repositories=urls,repositoriesLoaded=true,
                    loading=pending.isNotEmpty(),entries=mergedEntries(urls),repositoryErrors=it.repositoryErrors.filterKeys { url -> url in urls }) }
                loadCatalogs(pending)
            } } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.update { it.copy(repositorySaveError=true) } }
        }
        viewModelScope.launch { events.state.collect { event ->
            if(event!=null && event.status!=PackageInstaller.STATUS_PENDING_USER_ACTION) {
                mutable.update {
                    val current=it.session==event.session
                    it.copy(activePackage=if(current) null else it.activePackage,phase=if(current) null else it.phase,
                        session=if(current) null else it.session,inventoryRevision=it.inventoryRevision+1,
                        failures=if(event.status==PackageInstaller.STATUS_SUCCESS) it.failures-event.packageName
                            else if(current) it.failures+(event.packageName to if(event.status==PackageInstaller.STATUS_FAILURE_ABORTED) R.string.extension_install_cancelled else R.string.extension_install_failed) else it.failures)
                }
                events.consume(event)
            }
        } }
    }
    internal fun refresh() {
        if(!mutable.value.repositoriesLoaded || loadJob?.isActive==true || mutable.value.activePackage!=null) return
        loadCatalogs(mutable.value.repositories)
    }
    internal fun addRepository(url: String) = changeRepository { repositories.add(url) }
    internal fun removeRepository(url: String) = changeRepository { repositories.remove(url) }
    private fun changeRepository(action: suspend ()->Unit) {
        viewModelScope.launch { repositoryChanges.withLock {
            mutable.update { it.copy(repositorySaveError=false) }
            try { action() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.update { it.copy(repositorySaveError=true) } }
        } }
    }
    private fun mergedEntries(urls: List<String>)=urls.flatMap { loadedCatalogs[it].orEmpty() }.distinctBy { it.packageName }
    private fun loadCatalogs(urls: List<String>) {
        mutable.update { it.copy(loading=urls.isNotEmpty(),error=null) }
        loadJob=viewModelScope.launch {
            val permits=Semaphore(2)
            urls.map { url -> async { permits.withPermit {
                val error=try { loadedCatalogs[url]=repository.catalog(url); null }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) { message(error) }
                attemptedRepositories.add(url)
                mutable.update { old ->
                    val failures=if(error==null) old.repositoryErrors-url else old.repositoryErrors+(url to error)
                    old.copy(entries=mergedEntries(old.repositories),repositoryErrors=failures,
                        error=failures.values.firstOrNull().takeIf { failures.size==old.repositories.size })
                }
            } } }.awaitAll()
            mutable.update { it.copy(loading=false) }
            startAfterPermission()
        }
    }
    private fun CatalogExtension.installKey()="$repositoryUrl|$packageName|$versionCode|$signerSha256"
    internal fun permissionNeeded(entry: CatalogExtension) { saved["extension_permission"]=entry.installKey() }
    internal fun permissionResult(granted: Boolean) {
        permissionGranted=granted
        if(granted) startAfterPermission() else saved.remove<String>("extension_permission")
    }
    private fun startAfterPermission() {
        val entry=mutable.value.entries.firstOrNull { it.installKey()==saved.get<String>("extension_permission") } ?: return
        if(permissionGranted) { saved.remove<String>("extension_permission"); permissionGranted=false; start(entry) }
    }
    internal fun start(entry: CatalogExtension) {
        if(mutable.value.activePackage!=null || entry !in mutable.value.entries) return
        val request=requests.incrementAndGet()
        downloading=entry
        mutable.update { it.copy(activePackage=entry.packageName,phase=InstallPhase.DOWNLOADING,session=null,progress=0,failures=it.failures-entry.packageName) }
        transfer=viewModelScope.launch {
            try {
                ready=repository.download(entry) { value -> mutable.update { if(requests.get()==request) it.copy(progress=value) else it } }
                downloading=null
                mutable.update { it.copy(phase=InstallPhase.READY) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { downloading=null; mutable.update { it.copy(activePackage=null,phase=null,session=null,failures=it.failures+(entry.packageName to message(error))) } }
        }
    }
    /** A download finishing while hidden cannot launch Android's installer. */
    internal fun installReady() {
        val candidate=ready ?: return
        ready=null
        mutable.update { it.copy(phase=InstallPhase.INSTALLING) }
        transfer=viewModelScope.launch {
            try { installer.install(candidate) { session -> mutable.update { it.copy(session=session) } } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(activePackage=null,phase=null,session=null,failures=it.failures+(candidate.entry.packageName to message(error))) } }
            finally { withContext(NonCancellable+Dispatchers.IO) { candidate.file.delete() } }
        }
    }
    internal fun cancel() {
        if(mutable.value.phase==InstallPhase.INSTALLING) return
        requests.incrementAndGet()
        transfer?.cancel()
        downloading=null
        ready?.let { repository.discard(it.file) }; ready=null
        mutable.update { it.copy(activePackage=null,phase=null,session=null,progress=0) }
    }
    internal fun ownsConfirmation(event: InstallEvent): Boolean = mutable.value.let { it.session==event.session || it.activePackage==null }
    internal fun confirmationStarted(event: InstallEvent) = events.consume(event)
    override fun onCleared() { ready?.let { repository.discard(it.file) }; ready=null }
    private fun message(error: Exception) = when((error as? CatalogFailure)?.issue) {
        CatalogIssue.NETWORK -> R.string.extension_catalog_network
        CatalogIssue.INVALID -> R.string.extension_catalog_invalid
        CatalogIssue.SIGNATURE -> R.string.extension_signature_mismatch
        CatalogIssue.INCOMPATIBLE -> R.string.extension_not_compatible
        CatalogIssue.DOWNLOAD -> R.string.extension_download_failed
        CatalogIssue.REMOVED -> R.string.extension_repository_removed
        else -> R.string.extension_install_failed
    }
}
