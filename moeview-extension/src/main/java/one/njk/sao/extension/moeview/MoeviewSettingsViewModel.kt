package one.njk.sao.extension.moeview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.source.ExtensionProtocol
import java.util.UUID

class MoeviewSettingsViewModel(app: Application,saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean=false,val working: Boolean=false,val name: String="Moeview",val config: MoeviewConfig=MoeviewConfig(),
        val status: Int=R.string.ready,val finished: Boolean=false)
    private val runtime=MoeviewRuntime.get(app)
    private val mutable=MutableStateFlow(State()); val state=mutable.asStateFlow()
    val editing: String?=saved[ExtensionProtocol.PROFILE]
    val profile=editing ?: saved.get<String>("draft") ?: UUID.randomUUID().toString().also { saved["draft"]=it }
    private var previous: MoeviewProfile?=null
    val hasChanges get()=state.value.loaded && (state.value.name!=(previous?.name ?: "Moeview") || state.value.config!=(previous?.config ?: MoeviewConfig()))
    init { viewModelScope.launch {
        try {
            previous=editing?.let { runtime.profiles.get(it) }
            mutable.value=State(loaded=true,name=previous?.name ?: "Moeview",config=previous?.config ?: MoeviewConfig())
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { mutable.value=State(loaded=true,status=R.string.storage_error) }
    } }
    fun name(value: String) { mutable.value=state.value.copy(name=value) }
    fun configure(change: (MoeviewConfig)->MoeviewConfig) {
        if(!state.value.working) mutable.value=state.value.copy(config=change(state.value.config),status=R.string.ready)
    }
    fun save()=work {
        val state=state.value; require(state.name.isNotBlank()); state.config.validate()
        val row=runtime.profiles.save(profile,state.name,state.config)
        if(previous?.revision!=row.revision) runtime.invalidate(profile)
        mutable.value=state.copy(working=false,status=R.string.saved,finished=true)
    }
    fun check()=work {
        val config=state.value.config; config.validate()
        withContext(Dispatchers.IO) {
            val api=runtime.factory(config)
            try { api.search(config.board,config.tags,1,1,config.rating) } finally { runtime.close(api) }
        }
        mutable.value=state.value.copy(working=false,status=R.string.connected)
    }
    fun delete()=work {
        val row=runtime.profiles.get(profile)
        runtime.profiles.delete(profile,row.revision); runtime.invalidate(profile)
        mutable.value=state.value.copy(working=false,finished=true)
    }
    private fun work(block: suspend ()->Unit) {
        if(!state.value.loaded || state.value.working) return
        mutable.value=state.value.copy(working=true,status=R.string.working)
        viewModelScope.launch {
            try { block() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                val failure=MoeviewRuntime.failure(error)
                val cause=if(error is one.njk.sao.moeview.data.net.NoTransportException) error.cause else error
                val message=when {
                    cause is one.njk.sao.moeview.data.net.HttpStatusException && cause.challenged -> R.string.verification_needed
                    error is IllegalArgumentException -> R.string.invalid_configuration
                    failure.code in setOf("HTTP_401","HTTP_403") -> R.string.access_denied
                    failure.code=="HTTP_429" -> R.string.rate_limited
                    failure.code in setOf("NETWORK","TIMEOUT","TLS") -> R.string.network_error
                    failure.code=="INVALID_RESPONSE" -> R.string.invalid_response
                    else -> R.string.storage_error
                }
                mutable.value=state.value.copy(working=false,status=message)
            }
        }
    }
}
