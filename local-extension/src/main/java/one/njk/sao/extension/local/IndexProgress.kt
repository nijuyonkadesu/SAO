package one.njk.sao.extension.local

import androidx.core.view.isVisible
import one.njk.sao.extension.local.databinding.IndexProgressBinding

/** Render durable counts; animation is reserved for actual running work with an unknown total. */
internal fun IndexProgressBinding.bind(status: IndexStatus?, paused: Boolean, work: IndexWork) {
    val context=root.context
    phase.setText(when {
        status==null -> R.string.saved_first
        paused || status.paused -> R.string.album_paused
        status.permissionRequired -> R.string.album_permission
        status.folderErrors>0 -> R.string.album_unavailable
        status.scanning && work==IndexWork.RUNNING -> R.string.album_scanning
        status.scanning -> R.string.album_waiting_scan
        status.pending>0 && work==IndexWork.WAITING -> R.string.album_waiting
        status.pending>0 && status.contextSearch && status.contextPending>0 && work==IndexWork.RUNNING -> R.string.album_indexing
        status.pending>0 && work==IndexWork.RUNNING -> R.string.album_analyzing
        status.pending>0 -> R.string.album_stopped
        status.contextSearch && status.contextPending>0 && work==IndexWork.RUNNING -> R.string.album_embedding
        status.contextSearch && status.contextPending>0 && work==IndexWork.WAITING -> R.string.album_waiting
        status.contextSearch && status.contextPending>0 -> R.string.album_stopped
        !status.scanned && work==IndexWork.WAITING -> R.string.album_waiting_scan
        !status.scanned -> R.string.album_not_scanned
        status.failed+status.contextFailed>0 -> R.string.album_errors
        status.total==0 -> R.string.album_empty
        else -> R.string.album_complete
    })
    mediaCounts.isVisible=status!=null; processingCounts.isVisible=status!=null
    contextCounts.isVisible=status?.contextSearch==true
    if(status!=null) {
        mediaCounts.text=context.getString(if(status.scanning) R.string.media_counts_growing else R.string.media_counts,
            status.images,status.animated,status.videos)
        processingCounts.text=context.getString(R.string.processing_counts,status.processed,status.pending,status.failed)
        contextCounts.text=context.getString(R.string.context_counts,status.contextProcessed,status.contextPending,status.contextFailed)
    }
    val running=status!=null && !paused && !status.paused && work==IndexWork.RUNNING
    val show=status!=null && (status.total>0 && !status.scanning || running && status.scanning)
    if(progress.isIndeterminate && (!running || status?.scanning!=true)) progress.visibility=android.view.View.INVISIBLE
    progress.isIndeterminate=running && status?.scanning==true
    if(status!=null && !progress.isIndeterminate) {
        val steps=status.total.toLong()*(if(status.contextSearch) 2 else 1)
        val pending=status.pending.toLong()+(if(status.contextSearch) status.contextPending else 0)
        progress.setProgressCompat(if(steps==0L) 0 else ((steps-pending)*1000/steps).toInt(),false)
    }
    progress.isVisible=show
    val message=when {
        status==null -> null
        status.folderErrors>0 -> context.getString(R.string.folder_problem,status.folderErrors)
        status.failed+status.contextFailed>0 -> context.getString(R.string.processing_problem)
        else -> null
    }
    problems.isVisible=message!=null; problems.text=message
}
