package one.njk.sao.floating

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
class SourceProfilesViewModel @Inject constructor(private val profiles: SourceProfiles) : ViewModel() {
    private val mutable = MutableStateFlow(SourcesState())
    val state = mutable.asStateFlow()
    init { viewModelScope.launch {
        try { profiles.catalogs.collect { mutable.value = mutable.value.copy(catalog = it, loaded = true, error = false) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(loaded = true, error = true) }
    } }
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
            val detail = if (config is ImmichConfig) "Immich · " + getString(R.string.picker_source_scope,
                getString(if (config.albumId == null) R.string.immich_all_photos else R.string.immich_saved_album),
                getString(if (config.smartSearch) R.string.immich_smart_search else R.string.immich_metadata_search))
                else if (config is RaysConfig) getString(R.string.rays_source_description) else config.kind
            SourceListAdapter.Row(profile.id, profile.name, detail, profile.id == catalog.selectedId)
        }
