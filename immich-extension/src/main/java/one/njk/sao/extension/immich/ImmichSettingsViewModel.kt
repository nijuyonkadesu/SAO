package one.njk.sao.extension.immich

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.immichprovider.ImmichConfig
import one.njk.sao.immich.*
import one.njk.sao.source.ExtensionProtocol
import java.util.UUID

class ImmichSettingsViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean = false, val working: Boolean = false, val status: Int = R.string.immich_setup_description,
        val version: String? = null, val albums: List<ImmichAlbum> = emptyList(), val finished: Boolean = false)
    private val store = ConnectionStore(app)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val editingId: String? = saved[ExtensionProtocol.PROFILE]
    val profileId = editingId ?: UUID.randomUUID().toString()
    var name = "Immich"; var url = ""; var key = ""; var albumId: String? = null
    private var previous: ConnectionStore.Connection? = null
    internal val importConnection get()=previous
    internal val importsUnchanged get()=previous?.config?.let { it.serverUrl==url.trim() && it.apiKey==key.trim() && it.albumId==albumId }==true
    init { viewModelScope.launch {
        try {
            previous = editingId?.let { store.get(it) }
            previous?.let { name=it.name; url=it.config.serverUrl; key=it.config.apiKey; albumId=it.config.albumId }
            mutable.value = State(loaded=true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = State(loaded=true,status=R.string.immich_storage_error) }
    } }
    fun check(save: Boolean) {
        if (!state.value.loaded || state.value.working) return
        mutable.value = mutable.value.copy(working=true,status=R.string.immich_checking)
        viewModelScope.launch {
            try {
                require(name.isNotBlank())
                val config = ImmichConfig(url.trim(),key.trim(),albumId=albumId)
                config.connection
                if (save && previous?.config?.identity == config.identity) {
                    store.save(profileId,name,config)
                    mutable.value = mutable.value.copy(working=false,status=R.string.immich_saved,finished=true)
                    return@launch
                }
                val checked = withContext(Dispatchers.IO) { ImmichClient(config.connection,retryReadConnections=false).use { client ->
                    val version = client.api.version()
                    if (!version.supportsStructuredSearch) throw SetupFailure(R.string.immich_version_unsupported)
                    val permissions = client.api.apiKey()
                    if (listOf("asset.read","asset.view","asset.download","tag.read").any { !permissions.allows(it) })
                        throw SetupFailure(R.string.immich_permissions_missing)
                    val albums = if (permissions.allows("album.read")) client.api.albums() else emptyList()
                    if (config.albumId != null && albums.none { it.id == config.albumId }) throw SetupFailure(R.string.immich_album_unavailable)
                    client.api.search(MetadataSearch(SearchFilter(type=EnumFilter(eq="IMAGE"),
                        albumIds=config.albumId?.let { IdsFilter(any=listOf(it)) }),size=1))
                    version.toString() to albums
                } }
                if (save) store.save(profileId,name,config)
                mutable.value = State(true,false,if (save) R.string.immich_saved else R.string.immich_connected,checked.first,checked.second,save)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val status = when (error) {
                    is SetupFailure -> error.resource
                    is IllegalArgumentException -> R.string.immich_invalid_connection
                    is retrofit2.HttpException -> if (error.code() in setOf(401,403)) R.string.immich_auth_error else R.string.immich_server_error
                    is java.io.IOException -> R.string.immich_network_error
                    else -> R.string.immich_storage_error
                }
                mutable.value = mutable.value.copy(working=false,status=status)
            }
        }
    }
    fun delete() {
        if (state.value.working) return
        viewModelScope.launch {
            try { store.delete(profileId); mutable.value = mutable.value.copy(finished=true,status=R.string.source_removed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(status=R.string.immich_storage_error) }
        }
    }
    private class SetupFailure(val resource: Int) : Exception()
}
