package one.njk.sao.extension.findthatmeme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.source.ExtensionProtocol
import java.util.UUID

class FindThatMemeSettingsViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val loaded: Boolean = false, val working: Boolean = true, val name: String = "FindThatMeme",
        val config: FindThatMemeConfig = FindThatMemeConfig(), val status: Int = R.string.ready, val finished: Boolean = false)
    private val runtime = FindThatMemeRuntime.get(app)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    val editing: String? = saved[ExtensionProtocol.PROFILE]
    val profile = editing ?: saved.get<String>("draft") ?: UUID.randomUUID().toString().also { saved["draft"] = it }
    private var previous: FindThatMemeProfile? = null
    val hasChanges get() = state.value.loaded && (state.value.name != (previous?.name ?: "FindThatMeme") ||
        state.value.config != (previous?.config ?: FindThatMemeConfig()))

    init {
        viewModelScope.launch {
            try {
                previous = editing?.let { runtime.profiles.get(it) }
                val config = previous?.config ?: FindThatMemeConfig()
                mutable.value = State(loaded = true, working = false,
                    name = saved["name"] ?: previous?.name ?: "FindThatMeme",
                    config = FindThatMemeConfig(saved["terms"] ?: config.terms, saved["sensitive"] ?: config.sensitive))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = State(working = false, status = R.string.storage_error) }
        }
    }

    fun name(value: String) {
        if (!state.value.loaded || state.value.working) return
        saved["name"] = value
        mutable.value = state.value.copy(name = value, status = R.string.ready)
    }
    fun configure(config: FindThatMemeConfig) {
        if (!state.value.loaded || state.value.working) return
        saved["terms"] = config.terms
        saved["sensitive"] = config.sensitive
        mutable.value = state.value.copy(config = config, status = R.string.ready)
    }
    fun save() = work {
        val draft = state.value
        runtime.profiles.save(profile, draft.name, draft.config)
        mutable.value = draft.copy(working = false, status = R.string.saved, finished = true)
    }
    fun check() = work {
        val config = state.value.config
        config.validate()
        withContext(Dispatchers.IO) { runtime.api.search(config.terms, 0) }
        mutable.value = state.value.copy(working = false, status = R.string.connected)
    }
    fun delete() = work {
        val row = runtime.profiles.get(profile)
        runtime.profiles.delete(profile, row.revision)
        mutable.value = state.value.copy(working = false, finished = true)
    }
    private fun work(block: suspend () -> Unit) {
        if (!state.value.loaded || state.value.working) return
        mutable.value = state.value.copy(working = true, status = R.string.working)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val code = FindThatMemeRuntime.failure(error).code
                val message = when {
                    error is IllegalArgumentException -> R.string.invalid_configuration
                    code == "HTTP_429" -> R.string.rate_limited
                    code in setOf("NETWORK", "TIMEOUT", "TLS") -> R.string.network_error
                    code.startsWith("HTTP_") -> R.string.service_error
                    code == "INVALID_RESPONSE" -> R.string.invalid_response
                    else -> R.string.storage_error
                }
                mutable.value = state.value.copy(working = false, status = message)
            }
        }
    }
}
