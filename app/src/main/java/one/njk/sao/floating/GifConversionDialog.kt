package one.njk.sao.floating

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import one.njk.sao.R
import kotlin.coroutines.resume

/** Only an explicit, uncached Paste conversion can show this warning. */
internal object GifConversionDialog {
    suspend fun confirm(context: Context,cost: GifConversionCost?) {
        if(cost?.large!=true) return
        val accepted=withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                fun answer(value: Boolean) { if(continuation.isActive) continuation.resume(value) }
                val dialog=MaterialAlertDialogBuilder(context).setTitle(R.string.gif_conversion_large_title)
                    .setMessage(context.getString(R.string.gif_conversion_large_message,cost.width,cost.height,
                        context.resources.getQuantityString(R.plurals.gif_conversion_frame_count,cost.frames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),cost.frames)))
                    .setPositiveButton(R.string.gif_conversion_continue) { _,_ -> answer(true) }
                    .setNegativeButton(android.R.string.cancel) { _,_ -> answer(false) }.setOnCancelListener { answer(false) }.show()
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { dialog.dismiss() } }
            }
        }
        if(!accepted) throw CancellationException("GIF conversion cancelled")
    }
}
