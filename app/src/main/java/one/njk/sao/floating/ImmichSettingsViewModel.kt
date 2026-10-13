package one.njk.sao.floating

import androidx.lifecycle.SavedStateHandle
import java.util.UUID
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.njk.sao.R
import one.njk.sao.immich.*
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

data class ImmichSettingsState(val loaded: Boolean = false, val working: Boolean = false,
    val status: Int = R.string.immich_setup_description, val version: String? = null,
    val albums: List<ImmichAlbum> = emptyList(), val saved: Boolean = false)

@HiltViewModel
class ImmichSettingsViewModel @Inject constructor(private val settings: SourceProfiles, private val savedState: SavedStateHandle,
    private val dispatchers: FloatingDispatchers) : ViewModel() {
    private val mutable = MutableStateFlow(ImmichSettingsState())
    val state = mutable.asStateFlow()
    val editingId: String? = savedState[ImmichSettingsActivity.EXTRA_PROFILE]
    private val profileId = editingId ?: UUID.randomUUID().toString()
    var name = "Immich"
    var url = ""; var key = ""; var smart = true; var albumId: String? = null
    init { viewModelScope.launch {
        try {
            settings.current().profiles.firstOrNull { it.id == editingId }?.let { profile ->
                name = profile.name
                // Retain the old flag in stored identities; picker modes now choose the endpoint.
                (profile.configuration as ImmichConfig).let { url = it.serverUrl; key = it.apiKey; smart = it.smartSearch; albumId = it.albumId }
            }
            mutable.value = mutable.value.copy(loaded = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(loaded = true, status = R.string.immich_storage_error) }
    } }
    private fun config() = ImmichConfig(url.trim(), key.trim(), smart, albumId)
    fun check(save: Boolean) {
        if (!state.value.loaded || state.value.working) return
        mutable.value = mutable.value.copy(working = true, saved = false, status = R.string.immich_checking)
        viewModelScope.launch {
            try {
                require(name.isNotBlank())
                val profileName = name.trim()
                val config = config()
                config.connection
                val existing = settings.current().profiles.firstOrNull { it.id == profileId }
                if (save && existing?.configuration?.identity == config.identity) {
                    // Names are local settings; a rename must work while the server is offline.
                    settings.upsert(existing.copy(name = profileName), select = true)
                    mutable.value = mutable.value.copy(working = false, saved = true, status = R.string.immich_saved)
                    return@launch
                }
                val result = withContext(dispatchers.io) {
                    ImmichClient(config.connection).use { client ->
                    val version = ReadRetry.run { client.api.version() }
                    if (!version.supportsStructuredSearch) throw SetupFailure(R.string.immich_version_unsupported)
                    val key = ReadRetry.run { client.api.apiKey() }
                    if (listOf("asset.read", "asset.view", "asset.download", "tag.read").any { !key.allows(it) })
                        throw SetupFailure(R.string.immich_permissions_missing)
                    // Search-mode availability is checked on demand in the picker.
                    val albums = if (key.allows("album.read")) ReadRetry.run { client.api.albums() } else emptyList()
                    if (config.albumId != null && albums.none { it.id == config.albumId }) throw SetupFailure(R.string.immich_album_unavailable)
                    // A real authenticated read verifies search permission and the configured album filter.
                    ReadRetry.run { client.api.search(MetadataSearch(SearchFilter(type = EnumFilter(eq = "IMAGE"),
                        albumIds = config.albumId?.let { IdsFilter(any = listOf(it)) }), size = 1)) }
                    version.toString() to albums
                    }
                }
                if (save) settings.upsert(SourceProfile(profileId, profileName, config), select = true)
                mutable.value = ImmichSettingsState(true, false, if (save) R.string.immich_saved else R.string.immich_connected,
                    result.first, result.second, save)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val status = when (error) {
                    is SetupFailure -> error.messageRes
                    is IllegalArgumentException -> R.string.immich_invalid_connection
                    is HttpException -> if (error.code() == 401 || error.code() == 403) R.string.immich_auth_error else R.string.immich_server_error
                    is IOException -> R.string.immich_network_error
                    else -> R.string.immich_storage_error
                }
                mutable.value = mutable.value.copy(working = false, status = status)
            }
        }
    }
    fun useLocal() {
        if (!state.value.loaded || state.value.working) return
        mutable.value = mutable.value.copy(working = true)
        viewModelScope.launch {
            try {
                settings.select(null)
                mutable.value = mutable.value.copy(working = false, status = R.string.immich_using_local, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(working = false, status = R.string.immich_storage_error) }
        }
    }
    fun delete() {
        val id = editingId ?: return
        if (!state.value.loaded || state.value.working) return
        mutable.value = mutable.value.copy(working = true)
        viewModelScope.launch {
            try { settings.remove(id); mutable.value = mutable.value.copy(working = false, saved = true, status = R.string.source_removed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(working = false, status = R.string.immich_storage_error) }
        }
    }
    private class SetupFailure(val messageRes: Int) : Exception()
}
