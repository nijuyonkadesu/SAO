package one.njk.sao.usage

import javax.inject.Inject
import javax.inject.Singleton

/** One context-free, in-memory screen snapshot; no Activity, View, ViewModel or preference storage. */
@Singleton
class UsageSession @Inject constructor() {
    data class Snapshot(val saved: Map<String,Any?> = emptyMap(),val state: UsageUiState=UsageUiState(),
        val sourceNames: Map<String,String> = emptyMap())
    class Lease internal constructor(internal val key: Any,val snapshot: Snapshot)
    private var owner: Any?=null
    private var snapshot=Snapshot()
    @Synchronized fun acquire(): Lease {
        val key=Any(); owner=key
        return Lease(key,snapshot)
    }
    @Synchronized fun remember(lease: Lease,value: Snapshot) {
        if(owner===lease.key) snapshot=value
    }
}
