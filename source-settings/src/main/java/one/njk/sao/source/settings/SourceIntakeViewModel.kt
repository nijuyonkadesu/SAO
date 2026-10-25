package one.njk.sao.source.settings

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import one.njk.sao.source.*
import org.json.JSONObject

/** Public destination/queue state only. Backend settings and transfers stay with their respective owners. */
internal class SourceIntakeViewModel(app: Application,private val saved: SavedStateHandle): AndroidViewModel(app) {
    data class Route(val id: String,val name: String,val target: String,val checked: Long,val error: String?)
    data class Snapshot(val source: String,val changes: Uri,val done: Int,val pending: Int,val blocked: Int,val paused: Boolean,val error: String?,val routes: List<Route>)
    data class State(val options: ImportOptions?=null,val snapshot: Snapshot?=null,val target: ImportTarget?=null,
        val loading: Boolean=false,val busy: Boolean=false,val error: String?=null,val editable: Boolean=true)
    private class Configuration(val host: String,val profile: String,val revision: String,val languages: String,val options: suspend ()->ImportOptions) {
        val key="$host:$profile:$revision:$languages"
    }
    private val configuration=MutableStateFlow<Configuration?>(null)
    private val refresh=MutableStateFlow(0)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private var cachedOptions: ImportOptions?=null
    // Remember only when leaving the screen, in transient saved state; never write per scroll.
    var scrollPosition: Int?
        get()=saved["scroll"]
        set(value) { saved["scroll"]=value }
    fun configure(host: String,profile: String,revision: String,languages: String,options: suspend ()->ImportOptions) {
        val next=Configuration(host,profile,revision,languages,options)
        if(configuration.value?.key==next.key) return
        cachedOptions=null; mutable.value=State(loading=true); configuration.value=next
    }
    fun editable(value: Boolean) { mutable.update { it.copy(editable=value) } }
    fun target(id: String) {
        val target=state.value.options?.targets?.firstOrNull { it.id==id } ?: return
        saved["target"]=id; mutable.update { it.copy(target=target) }
    }
    fun reload() { refresh.value++ }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun watch() {
        combine(configuration,refresh) { config,_->config }.collectLatest { config ->
            if(config==null) return@collectLatest
            try {
                mutable.update { it.copy(loading=true,error=null) }
                val options=cachedOptions ?: withContext(Dispatchers.IO) { config.options() }.also { cachedOptions=it }
                val target=options.targets.firstOrNull { it.id==saved.get<String>("target") } ?: options.targets.firstOrNull()
                mutable.update { it.copy(options=options,target=target) }
                val first=read(config)
                update(first)
                callbackFlow {
                    val resolver=getApplication<Application>().contentResolver
                    val observer=object: ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) { trySend(Unit) }
                    }
                    resolver.registerContentObserver(first.changes,false,observer)
                    trySend(Unit) // Close the read/register race; subsequent reads require an actual change.
                    awaitClose { resolver.unregisterContentObserver(observer) }
                }.conflate().collect { delay(350); update(read(config)) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(loading=false,error=(error as? ExtensionFailure)?.code ?: "REQUEST_FAILED") } }
        }
    }
    private fun update(snapshot: Snapshot) { mutable.update { it.copy(snapshot=snapshot,loading=false,error=null) } }
    private suspend fun read(config: Configuration,operation: String=ImportSettings.STATE,route: String?=null): Snapshot {
        val reply=ExtensionClient(getApplication(),ImportSettings.service(config.host)) { config.languages }.request(config.profile,operation,
            JSONObject().put("revision",config.revision).put("route",route))
        val changes=Uri.parse(reply.getString("changes"))
        require(changes==ImportSettings.changes(config.host))
        val rows=reply.getJSONArray("routes"); require(rows.length()<=512)
        return Snapshot(reply.getString("source"),changes,reply.getInt("done"),reply.getInt("pending"),reply.getInt("blocked"),
            reply.getBoolean("paused"),SourceWire.nullable(reply,"error"),List(rows.length()) { rows.getJSONObject(it).let { row ->
                Route(row.getString("id"),row.getString("name"),row.getString("target"),row.getLong("checked"),SourceWire.nullable(row,"error"))
            } })
    }
    fun action(operation: String,route: String?=null) {
        val config=configuration.value ?: return
        if(!state.value.editable || state.value.busy) return
        mutable.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            try { update(read(config,operation,route)) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.update { it.copy(error=(error as? ExtensionFailure)?.code ?: "REQUEST_FAILED") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
}
