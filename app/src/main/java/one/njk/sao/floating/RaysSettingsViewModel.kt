package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.rays.*
import java.util.UUID
import javax.inject.Inject

data class RaysSettingsState(val loaded: Boolean = false, val working: Boolean = false,
    val status: Int = R.string.rays_setup_description)

@HiltViewModel
class RaysSettingsViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val profiles: SourceProfiles, savedState: SavedStateHandle,
    private val dispatchers: FloatingDispatchers) : ViewModel() {
    private val mutable = MutableStateFlow(RaysSettingsState())
    val state = mutable.asStateFlow()
    val editingId: String? = savedState[RaysSettingsActivity.EXTRA_PROFILE]
    private val floating: Boolean = savedState[SourceSetupActivity.EXTRA_FLOATING] ?: false
    private val profileId = editingId ?: UUID.randomUUID().toString()
    var name = "Rays"
    var packageName: String = savedState[RaysSettingsActivity.EXTRA_PACKAGE] ?: RaysClient.PACKAGE
        private set
    init { viewModelScope.launch {
        try {
            profiles.current().profiles.firstOrNull { it.id == editingId }?.let {
                name = it.name; packageName = (it.configuration as RaysConfig).packageName
            }
            mutable.value = mutable.value.copy(loaded = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(loaded = true, status = R.string.source_setup_storage_error) }
    } }
    fun check(save: Boolean) = work {
        require(name.isNotBlank())
        RaysClient(context, packageName, dispatchers.io).search("", size = 1)
        if (save) {
            profiles.upsert(SourceProfile(profileId, name.trim(), RaysConfig(packageName)), select = !floating)
            if (floating) profiles.selectFloating(profileId)
        }
        mutable.value = mutable.value.copy(status = if (save) R.string.rays_saved else R.string.rays_connected)
    }
    fun delete() { editingId?.let { id -> work {
        profiles.remove(id); mutable.value = mutable.value.copy(status = R.string.source_removed)
    } } }
    private fun work(block: suspend () -> Unit) {
        if (!state.value.loaded || state.value.working) return
        mutable.value = mutable.value.copy(working = true, status = R.string.source_setup_checking)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(status = when (error) {
                is RaysException -> error.problem.messageRes()
                is IllegalArgumentException -> R.string.rays_invalid_name
                else -> R.string.rays_connection_error
            }) }
            finally { mutable.value = mutable.value.copy(working = false) }
        }
    }
}

internal fun RaysProblem.messageRes() = when (this) {
    RaysProblem.NOT_INSTALLED -> R.string.rays_not_installed
    RaysProblem.UPDATE_REQUIRED -> R.string.rays_update_required
    RaysProblem.ACCESS_DENIED -> R.string.rays_access_denied
    RaysProblem.DISCONNECTED -> R.string.rays_connection_error
    RaysProblem.INVALID_RESPONSE -> R.string.rays_invalid_response
}
