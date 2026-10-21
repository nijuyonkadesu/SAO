package one.njk.sao.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import one.njk.sao.R
import kotlin.coroutines.resume

/** Appears only for an explicit Discord delivery over its free-tier threshold. */
internal object AttachmentLimitDialog {
    suspend fun choose(context: Context, bytes: Long, canReduce: Boolean): Boolean? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            fun complete(value: Boolean?) { if(continuation.isActive) continuation.resume(value) }
            val message=context.getString(R.string.media_discord_oversized,Formatter.formatShortFileSize(context,bytes))+
                context.getString(if(canReduce) R.string.media_discord_smaller_description else R.string.media_discord_original_only)
            val builder=MaterialAlertDialogBuilder(context).setTitle(R.string.media_discord_size_title).setMessage(message)
                .setPositiveButton(R.string.media_use_original) { _,_ -> complete(false) }
                .setNegativeButton(android.R.string.cancel) { _,_ -> complete(null) }
                .setOnCancelListener { complete(null) }
            if(canReduce) builder.setNeutralButton(R.string.media_smaller_copy) { _,_ -> complete(true) }
            val dialog=builder.show()
            continuation.invokeOnCancellation {
                if(Looper.myLooper()==Looper.getMainLooper()) dialog.dismiss()
                else Handler(Looper.getMainLooper()).post { dialog.dismiss() }
            }
        }
    }
}
