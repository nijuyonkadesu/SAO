package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import one.njk.sao.R
import javax.inject.Inject

data class SourcesState(val catalog: SourceCatalog = SourceCatalog(), val loaded: Boolean = false,
    val saving: Boolean = false, val error: Boolean = false, val allEnabled: Boolean = false,
    val floatingCatalog: SourceCatalog = SourceCatalog(), val floatingDisabled: Set<String> = emptySet(),
    val installedExtensions: List<InstalledExtensionApp> = emptyList())

@HiltViewModel
class SourceProfilesViewModel @Inject constructor(private val profiles: SourceProfiles, private val extensions: SourceExtensions) : ViewModel() {
    private val mutable = MutableStateFlow(SourcesState())
    val state = mutable.asStateFlow()
    private val available = MutableStateFlow<Set<String>?>(null)
    private fun installed(profile: one.njk.sao.source.SourceProfile, keys: Set<String>) = when(val config=profile.configuration) {
        is ExtensionConfig -> config.component.flattenToString() in keys
        is RaysConfig -> config.packageName in keys
        is AllSourceConfiguration -> config.members.isNotEmpty()
        else -> false
    }
    init { viewModelScope.launch {
        try { combine(profiles.snapshots,available) { snapshot,keys ->
            val catalog = snapshot.catalog
            val visible=if(keys==null) emptyList() else catalog.profiles.filter { installed(it,keys) }
            val shown = SourceCatalog(visible,catalog.selectedId?.takeIf { id -> visible.any { it.id==id } })
            SourcesState(catalog=shown,loaded=keys!=null,allEnabled=catalog.profiles.any { it.id==ALL_SOURCE_ID },
                floatingCatalog=shown.forFloating(snapshot.floatingDisabled, preferredId=snapshot.floatingSelectedId),floatingDisabled=snapshot.floatingDisabled)
        }.collect { state -> mutable.value = state.copy(saving=mutable.value.saving, error=mutable.value.error, installedExtensions=mutable.value.installedExtensions) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(loaded = true, error = true) }
    } }
    private var refreshJob: kotlinx.coroutines.Job? = null
    fun refreshExtensions() {
        if(refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                mutable.value=mutable.value.copy(installedExtensions=extensions.installedApps())
                val providers=extensions.installed()
                val keys=providers.map { it.component.flattenToString() }.toSet()+extensions.installedRays()
                available.value=keys
                var failed=false
                for (extension in providers) {
                    try { extensions.migrate(extension); extensions.refresh(extension) }
                    catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { failed=true }
                }
                val catalog=profiles.current()
                profiles.available(catalog.profiles.filter { installed(it,keys) && it.id != ALL_SOURCE_ID }.map { it.id }.toSet())
                val resolved=profiles.current()
                if(resolved.selected?.let { installed(it,keys) }!=true) profiles.select(resolved.profiles.firstOrNull { installed(it,keys) }?.id)
                mutable.value=mutable.value.copy(error=failed)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
        }
    }
    fun enableAll(enabled: Boolean) {
        if (!state.value.loaded || state.value.saving || state.value.allEnabled == enabled) return
        mutable.value = mutable.value.copy(saving = true)
        viewModelScope.launch {
            try { profiles.enableAll(enabled); mutable.value = mutable.value.copy(saving = false, error = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(saving = false, error = true) }
        }
    }
    fun select(id: String?) = select(id, floating = false)
    fun selectFloating(id: String?) = select(id, floating = true)
    private fun select(id: String?, floating: Boolean) {
        val catalog = if (floating) state.value.floatingCatalog else state.value.catalog
        if (!state.value.loaded || state.value.saving || catalog.selectedId == id) return
        mutable.value = mutable.value.copy(saving = true)
        viewModelScope.launch {
            try { if (floating) profiles.selectFloating(id) else profiles.select(id); mutable.value = mutable.value.copy(saving = false, error = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(saving = false, error = true) }
        }
    }
    fun enableInFloating(id: String, enabled: Boolean) {
        if (!state.value.loaded || state.value.saving || (id !in state.value.floatingDisabled) == enabled) return
        mutable.value = mutable.value.copy(saving = true)
        viewModelScope.launch {
            try { profiles.enableInFloating(id, enabled); mutable.value = mutable.value.copy(saving = false, error = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(saving = false, error = true) }
        }
    }
}

internal fun Context.sourceRows(catalog: SourceCatalog, floatingDisabled: Set<String> = emptySet(), saving: Boolean = false): List<SourceListAdapter.Row> =
    catalog.profiles.map { profile ->
            val config = profile.configuration
            val detail = when (config) {
                is ExtensionConfig -> listOf(config.providerName,config.summary).filter(String::isNotBlank).joinToString(" · ")
                is AllSourceConfiguration -> getString(R.string.source_all_description)
                is RaysConfig -> getString(R.string.rays_source_description)
                else -> getString(R.string.source_extension_missing)
            }
            SourceListAdapter.Row(profile.id, profile.name, detail, profile.id == catalog.selectedId, profile.id !in floatingDisabled, !saving)
        }
