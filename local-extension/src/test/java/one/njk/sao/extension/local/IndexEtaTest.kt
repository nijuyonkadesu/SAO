package one.njk.sao.extension.local

import org.junit.Assert.*
import org.junit.Test

class IndexEtaTest {
    private val profile=Library("profile","Fixture","revision",emptyList())
    private fun overview(status: IndexStatus,folders: Map<String,IndexStatus> = mapOf("active" to status))=IndexOverview(profile,status,folders)
    private fun measured(labels: Double?=1000.0,context: Double?=4000.0,root: String?="active")=
        IndexTimingSnapshot(profile.id,profile.revision,root,labels,context)
    @Test fun combinesSeparateStageCostsAndSkipsStagesWithNoPendingWork() {
        val status=IndexStatus(10,4,1,false,0,contextSearch=true,contextProcessed=6)
        assertEquals(IndexEta.Remaining(20_000),indexEstimates(overview(status),IndexWork.RUNNING,measured()).total)
        assertEquals(IndexEta.Remaining(16_000),indexEstimates(overview(status.copy(pending=0)),IndexWork.RUNNING,measured(labels=null)).total)
        assertEquals(IndexEta.Calculating,indexEstimates(overview(status),IndexWork.RUNNING,measured(context=null)).total)
    }
    @Test fun pausedAlbumsAreExcludedAndQueuedAlbumsNeverShowAnActiveCountdown() {
        val status=IndexStatus(10,10,0,false,0)
        val result=indexEstimates(overview(status.copy(total=20,pending=20),mapOf("active" to status,"paused" to status.copy(paused=true,folderErrors=1))),IndexWork.RUNNING,measured())
        assertEquals(IndexEta.Remaining(10_000),result.total); assertNull(result.folders["paused"])
        val queued=indexEstimates(overview(status,mapOf("other" to status)),IndexWork.RUNNING,measured())
        assertEquals(IndexEta.Waiting,queued.folders["other"])
        assertEquals(IndexEta.Waiting,indexEstimates(overview(status),IndexWork.WAITING,measured()).total)
        assertNull(indexEstimates(overview(status),IndexWork.IDLE,measured()).total)
        assertNull(indexEstimates(overview(status.copy(paused=true)),IndexWork.RUNNING,measured()).total)
    }
    @Test fun scanningCompletionAndConfigurationChangesDoNotReuseAnInvalidEstimate() {
        val status=IndexStatus(10,5,0,true,0)
        assertEquals(IndexEta.Scanning,indexEstimates(overview(status),IndexWork.RUNNING,measured()).total)
        assertEquals(IndexEta.Waiting,indexEstimates(overview(status),IndexWork.RUNNING,null).total)
        assertNull(indexEstimates(overview(status.copy(scanning=false,pending=0)),IndexWork.RUNNING,measured()).total)
        assertEquals(IndexEta.Waiting,indexEstimates(overview(status.copy(scanning=false)),IndexWork.RUNNING,measured().copy(revision="old")).total)
    }
    @Test fun recentWindowAdaptsToChangedSpeedAndNeedsWarmupForEachStage() {
        val speed=IndexSpeed()
        repeat(2) { speed.record(IndexStage.LABELS,10_000) }
        assertNull(speed.average(IndexStage.LABELS)); assertNull(speed.average(IndexStage.CONTEXT))
        speed.record(IndexStage.LABELS,10_000); assertEquals(10_000.0,speed.average(IndexStage.LABELS)!!,0.0)
        repeat(32) { speed.record(IndexStage.LABELS,1000) }
        assertEquals(1000.0,speed.average(IndexStage.LABELS)!!,0.0)
    }
    @Test fun measurementsPublishOnWorkEventsAndNewSessionsDiscardPausedTime() {
        var now=0L
        val emitted=mutableListOf<IndexTimingSnapshot?>()
        val session=IndexTimingSession(profile.id,profile.revision,{ now },emitted::add)
        session.start("active")
        repeat(3) { now+=100; session.completed(IndexStage.LABELS,100) }
        assertEquals(100.0,emitted.last()!!.labelMillis!!,0.0)
        val count=emitted.size
        repeat(5) { now+=100; session.completed(IndexStage.LABELS,100) }
        assertEquals(count,emitted.size)
        session.start("next-album"); assertEquals(count,emitted.size)
        now+=1000; session.completed(IndexStage.LABELS,100)
        assertEquals("next-album",emitted.last()!!.root)
        session.finish(); assertNull(emitted.last())
        now+=60_000
        val resumed=IndexTimingSession(profile.id,profile.revision,{ now },emitted::add)
        resumed.start("active"); assertNull(emitted.last()!!.labelMillis)
        repeat(3) { now+=200; resumed.completed(IndexStage.LABELS,200) }
        assertEquals(200.0,emitted.last()!!.labelMillis!!,0.0)
    }
}
