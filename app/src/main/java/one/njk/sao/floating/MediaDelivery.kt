package one.njk.sao.floating

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent

/** Binary attachment handoff. The exact file MIME, stream URI and temporary grant travel together. */
object MediaDelivery {
    fun intent(media: ExportedMedia, packageName: String?, title: String) = Intent(Intent.ACTION_SEND).apply {
        type = media.mimeType
        putExtra(Intent.EXTRA_STREAM, media.uri)
        clipData = ClipData(ClipDescription(title, arrayOf(media.mimeType)), ClipData.Item(media.uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (packageName != null) setPackage(packageName)
    }
}
