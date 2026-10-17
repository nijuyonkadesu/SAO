package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.njk.sao.R
import javax.inject.Inject

data class SourcesState(val catalog: SourceCatalog = SourceCatalog(), val loaded: Boolean = false,
    val saving: Boolean = false, val error: Boolean = false)

@HiltViewModel
class SourceProfilesViewModel @Inject constructor(private val profiles: SourceProfiles, private val extensions: SourceExtensions) : ViewModel() {
    private val mutable = MutableStateFlow(SourcesState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {
        try { profiles.catalogs.collect { mutable.value = mutable.value.copy(catalog = it, loaded = true, error = false) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(loaded = true, error = true) }
    } }
    private var refreshJob: kotlinx.coroutines.Job? = null
    fun refreshExtensions() {
        if(refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                for (extension in extensions.installed()) { extensions.migrate(extension); extensions.refresh(extension) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = true) }
        }
    }
    fun select(id: String?) {
        if (!state.value.loaded || state.value.saving || state.value.catalog.selectedId == id) return
        mutable.value = mutable.value.copy(saving = true)
        viewModelScope.launch {
            try { profiles.select(id); mutable.value = mutable.value.copy(saving = false, error = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(saving = false, error = true) }
        }
    }
}

internal fun Context.sourceRows(catalog: SourceCatalog): List<SourceListAdapter.Row> =
    listOf(SourceListAdapter.Row(null, getString(R.string.picker_source_samples), getString(R.string.source_samples_description), catalog.selectedId == null)) +
        catalog.profiles.map { profile ->
            val config = profile.configuration
            val detail = when (config) {
                is ExtensionConfig -> listOf(config.providerName,config.summary).filter(String::isNotBlank).joinToString(" · ")
                is RaysConfig -> getString(R.string.rays_source_description)
                else -> getString(R.string.source_extension_missing)
            }
            SourceListAdapter.Row(profile.id, profile.name, detail, profile.id == catalog.selectedId)
        }
