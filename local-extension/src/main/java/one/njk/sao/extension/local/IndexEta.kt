package one.njk.sao.extension.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.ceil

internal enum class IndexStage { LABELS, CONTEXT }
sealed interface IndexEta {
    data object Scanning : IndexEta
    data object Waiting : IndexEta
    data object Calculating : IndexEta
    data class Remaining(val millis: Long) : IndexEta
}
internal data class IndexTimingSnapshot(val profile: String, val revision: String, val root: String?,
    val labelMillis: Double?, val contextMillis: Double?)
data class IndexEstimates(val total: IndexEta?=null,val folders: Map<String,IndexEta?> = emptyMap())

/** Transient worker measurements, not preferences or database progress writes. No ticking timer. */
internal object IndexTimings { val current=MutableStateFlow<IndexTimingSnapshot?>(null) }

/** Separate recent costs for OCR/labels and Context, including model startup and cloud reads. */
internal class IndexSpeed {
    private val samples=IndexStage.entries.associateWith { ArrayDeque<Long>() }
    fun record(stage: IndexStage, millis: Long) {
        require(millis>=0)
        samples.getValue(stage).apply { addLast(millis.coerceAtLeast(1)); if(size>32) removeFirst() }
    }
    fun average(stage: IndexStage): Double? = samples.getValue(stage).takeIf { it.size>=3 }?.average()
}

internal class IndexTimingSession(private val profile: String, private val revision: String,
    private val clock: () -> Long, private val publish: (IndexTimingSnapshot?) -> Unit) {
    private val speed=IndexSpeed()
    private var root: String?=null
    private var published: IndexTimingSnapshot?=null
    private var lastPublished=0L
    init { publish(snapshot()) }
    private fun snapshot()=IndexTimingSnapshot(profile,revision,root,speed.average(IndexStage.LABELS),speed.average(IndexStage.CONTEXT))
    fun start(root: String) {
        if(this.root==root) return
        this.root=root
        if(published==null || clock()-lastPublished>=1000) emit()
    }
    fun completed(stage: IndexStage, millis: Long) {
        speed.record(stage,millis)
        val now=clock(); val next=snapshot()
        if(now-lastPublished>=1000 || (published?.labelMillis==null)!=(next.labelMillis==null) ||
            (published?.contextMillis==null)!=(next.contextMillis==null)) emit()
    }
    private fun emit() { published=snapshot(); lastPublished=clock(); publish(published) }
    fun finish() { publish(null) }
}

internal fun indexEstimates(overview: IndexOverview?, work: IndexWork, timing: IndexTimingSnapshot?): IndexEstimates {
    if(overview==null) return IndexEstimates()
    val active=overview.folders.values.filterNot { it.paused }
    val total=overview.total.copy(total=active.sumOf { it.total },pending=active.sumOf { it.pending },
        failed=active.sumOf { it.failed },contextProcessed=active.sumOf { it.contextProcessed },contextFailed=active.sumOf { it.contextFailed },
        folderErrors=active.sumOf { it.folderErrors },permissionRequired=active.any { it.permissionRequired },
        scanning=active.any { it.scanning },paused=overview.total.paused || active.isEmpty())
    val measured=timing?.takeIf { it.profile==overview.library.id && it.revision==overview.library.revision }
    return IndexEstimates(estimate(total,overview.library.paused,work,measured),
        overview.folders.mapValues { (root,status) -> estimate(status,overview.library.paused,work,measured,root) })
}

private fun estimate(status: IndexStatus, paused: Boolean, work: IndexWork, timing: IndexTimingSnapshot?, root: String?=null): IndexEta? {
    val context=if(status.contextSearch) status.contextPending else 0
    if(paused || status.paused || work==IndexWork.IDLE || status.folderErrors>0) return null
    if(status.scanning) return if(work==IndexWork.RUNNING && timing!=null) IndexEta.Scanning else IndexEta.Waiting
    if(status.pending==0 && context==0) return null
    if(work!=IndexWork.RUNNING || timing==null || timing.root==null || root!=null && root!=timing.root) return IndexEta.Waiting
    if(status.pending>0 && timing.labelMillis==null || context>0 && timing.contextMillis==null) return IndexEta.Calculating
    val millis=status.pending*(timing.labelMillis ?: 0.0)+context*(timing.contextMillis ?: 0.0)
    return IndexEta.Remaining(ceil(millis).toLong().coerceAtLeast(1))
}
