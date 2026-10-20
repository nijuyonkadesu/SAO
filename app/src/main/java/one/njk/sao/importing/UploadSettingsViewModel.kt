package one.njk.sao.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.njk.sao.database.SettingsDataStore
import javax.inject.Inject

/** Upload policy belongs to SAO, independently of profile browsing and extension credentials. */
@HiltViewModel
class UploadSettingsViewModel @Inject constructor(settings: SettingsDataStore,private val imports: MediaImports): ViewModel() {
    data class State(val wifiOnly: Boolean=false,val loaded: Boolean=false,val saving: Boolean=false,val error: Boolean=false)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    init { viewModelScope.launch {
        try { settings.uploadsWifiOnlyFlow().collect { value -> mutable.update { it.copy(wifiOnly=value,loaded=true) } } }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { mutable.update { it.copy(error=true) } }
    } }
    fun wifiOnly(value: Boolean) {
        if(!state.value.loaded || state.value.saving || state.value.wifiOnly==value) return
        mutable.update { it.copy(saving=true,error=false) }
        viewModelScope.launch {
            try { imports.uploadsWifiOnly(value) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { mutable.update { it.copy(error=true) } }
            finally { mutable.update { it.copy(saving=false) } }
        }
    }
}
