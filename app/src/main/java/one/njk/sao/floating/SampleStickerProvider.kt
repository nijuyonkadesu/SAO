package one.njk.sao.floating

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import java.io.File

/** The official WhatsApp pack protocol, restricted to this immutable sample pack. */
class SampleStickerProvider : ContentProvider() {
    private val items by lazy {
        val json = requireContext().assets.open("floating/catalog.json").bufferedReader().use { JSONArray(it.readText()) }
        List(json.length()) { json.getJSONObject(it) }.filterNot { it.optBoolean("animated") }
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val segments = uri.pathSegments
        if (segments == listOf("metadata") || segments == listOf("metadata", PACK)) {
            return MatrixCursor(arrayOf("sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher",
                "sticker_pack_icon", "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website",
                "image_data_version", "whatsapp_will_not_cache_stickers", "animated_sticker_pack")).apply {
                addRow(arrayOf<Any>(PACK, "SAO Reactions", "SAO", "tray.png", "", "", "", "", "", "", "1", 0, 0))
            }
        }
        require(segments == listOf("stickers", PACK)) { "Unknown sticker URI" }
        return MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")).apply {
            items.forEach { item ->
                val emojis = item.getJSONArray("emojis")
                addRow(arrayOf(item.getString("id") + ".webp",
                    List(emojis.length()) { emojis.getString(it) }.joinToString(","), item.getString("title")))
            }
        }
    }
    override fun getType(uri: Uri): String = when (uri.pathSegments.firstOrNull()) {
        "metadata" -> "vnd.android.cursor.dir/vnd.${requireContext().packageName}.metadata"
        "stickers" -> "vnd.android.cursor.dir/vnd.${requireContext().packageName}.stickers"
        "stickers_asset" -> if (validatedFilename(uri) == "tray.png") "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown sticker URI")
    }
    private fun validatedFilename(uri: Uri): String {
        val parts = uri.pathSegments
        require(parts.size == 3 && parts[0] == "stickers_asset" && parts[1] == PACK)
        val allowed = items.map { it.getString("id") + ".webp" } + "tray.png"
        require(parts[2] in allowed) { "Unknown sticker asset" }
        return parts[2]
    }
    @Synchronized override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        require(mode == "r") { "Read-only provider" }
        val filename = validatedFilename(uri)
        val dir = File(requireContext().filesDir, "sample_sticker_pack/v1").apply { mkdirs() }
        val file = File(dir, filename)
        if (!file.exists()) requireContext().assets.open("floating/$filename").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
    companion object { const val PACK = "sao_samples" }
}
