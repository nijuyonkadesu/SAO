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
import one.njk.sao.R
import javax.inject.Inject

internal enum class InstallPhase { DOWNLOADING, READY, INSTALLING }
internal data class CatalogState(val entries: List<CatalogExtension> = emptyList(),val loading: Boolean = false,
    val error: Int? = null,val activePackage: String? = null,val phase: InstallPhase? = null,val progress: Int = 0,
    val failures: Map<String,Int> = emptyMap(),val inventoryRevision: Int = 0,val session: Int? = null)

@HiltViewModel
class ExtensionCatalogViewModel @Inject constructor(private val repository: CatalogRepository,
    private val installer: ExtensionInstaller,private val events: ExtensionInstallEvents,private val saved: SavedStateHandle): ViewModel() {
    private val mutable=MutableStateFlow(CatalogState())
    internal val state=mutable.asStateFlow()
    internal val installEvents=events.state
    private var loadJob: Job?=null
    private var transfer: Job?=null
    private var ready: VerifiedExtension?=null
    private var permissionGranted=false
    private val requests=java.util.concurrent.atomic.AtomicLong()
    init {
        refresh()
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
        if(loadJob?.isActive==true || mutable.value.activePackage!=null) return
        mutable.update { it.copy(loading=true,error=null) }
        loadJob=viewModelScope.launch {
            try {
                val entries=repository.catalog()
                mutable.update { it.copy(entries=entries,loading=false) }
                startAfterPermission()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(loading=false,error=message(error)) } }
        }
    }
    internal fun permissionNeeded(entry: CatalogExtension) { saved["extension_permission"]=entry.packageName }
    internal fun permissionResult(granted: Boolean) {
        permissionGranted=granted
        if(granted) startAfterPermission() else saved.remove<String>("extension_permission")
    }
    private fun startAfterPermission() {
        val entry=mutable.value.entries.firstOrNull { it.packageName==saved.get<String>("extension_permission") } ?: return
        if(permissionGranted) { saved.remove<String>("extension_permission"); permissionGranted=false; start(entry) }
    }
    internal fun start(entry: CatalogExtension) {
        if(mutable.value.activePackage!=null || entry !in mutable.value.entries) return
        val request=requests.incrementAndGet()
        mutable.update { it.copy(activePackage=entry.packageName,phase=InstallPhase.DOWNLOADING,session=null,progress=0,failures=it.failures-entry.packageName) }
        transfer=viewModelScope.launch {
            try {
                ready=repository.download(entry) { value -> mutable.update { if(requests.get()==request) it.copy(progress=value) else it } }
                mutable.update { it.copy(phase=InstallPhase.READY) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(activePackage=null,phase=null,session=null,failures=it.failures+(entry.packageName to message(error))) } }
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
        else -> R.string.extension_install_failed
    }
}
