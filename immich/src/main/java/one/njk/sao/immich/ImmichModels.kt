package one.njk.sao.immich

/** API subset verified against Immich v3.3.1. Unknown response fields are deliberately tolerated. */
data class ServerVersion(val major: Int, val minor: Int, val patch: Int) {
    val supportsStructuredSearch get() = major == 3 && minor >= 2
    override fun toString() = "$major.$minor.$patch"
}
data class ServerFeatures(val search: Boolean = false, val smartSearch: Boolean = false, val ocr: Boolean? = null)
data class ApiKeyInfo(val id: String, val name: String, val permissions: List<String>) {
    fun allows(permission: String) = "all" in permissions || permission in permissions
}
data class ImmichAsset(val id: String, val originalFileName: String, val type: String,
    val updatedAt: String, val originalMimeType: String? = null, val isEdited: Boolean = false,
    val duration: Int? = null, val tags: List<ImmichTag> = emptyList(), val exifInfo: AssetExif? = null,
    val width: Long? = null, val height: Long? = null, val fileCreatedAt: String? = null,
    val fileModifiedAt: String? = null, val isFavorite: Boolean = false, val visibility: String = "timeline")
data class AssetExif(val description: String? = null, val dateTimeOriginal: String? = null,
    val latitude: Double? = null, val longitude: Double? = null, val rating: Int? = null)
data class ImmichTag(val id: String, val name: String, val value: String,
    val color: String? = null, val parentId: String? = null)
data class ImmichAlbum(val id: String, val albumName: String, val description: String? = null,
    val assetCount: Int = 0, val albumThumbnailAssetId: String? = null, val shared: Boolean = false,
    val assets: List<ImmichAsset> = emptyList())

data class IdsFilter(val all: List<String>? = null, val any: List<String>? = null, val none: List<String>? = null)
data class EnumFilter(val eq: String? = null, val `in`: List<String>? = null)
data class StringFilter(val eq: String? = null, val like: String? = null)
/** An explicit null date equality matches assets without a trash timestamp. */
data class DateEqualityFilter(val eq: String?)
data class IdEqualityFilter(val eq: String)
data class SearchBranch(val originalFileName: StringFilter? = null, val description: StringFilter? = null,
    val tagIds: IdsFilter? = null, val id: IdEqualityFilter? = null)
data class SearchFilter(val albumIds: IdsFilter? = null, val tagIds: IdsFilter? = null,
    val type: EnumFilter? = null, val visibility: EnumFilter? = null,
    val trashedAt: DateEqualityFilter? = null, val or: List<SearchBranch>? = null,
    val originalFileName: StringFilter? = null, val originalPath: StringFilter? = null,
    val description: StringFilter? = null, val ocr: TextMatchFilter? = null,
    val personIds: IdsFilter? = null, val country: StringFilter? = null,
    val state: StringFilter? = null, val city: StringFilter? = null)
data class TextMatchFilter(val matches: String)
data class ImmichPerson(val id: String, val name: String = "")
data class ImmichPeople(val people: List<ImmichPerson> = emptyList(), val hasNextPage: Boolean = false)
data class SearchOrder(val direction: String = "desc", val field: String = "fileCreatedAt")
data class MetadataSearch(val filter: SearchFilter = SearchFilter(), val cursor: String? = null,
    val size: Int = 60, val orderBy: SearchOrder = SearchOrder(), val withExif: Boolean = true) {
    init { require(size in 1..1000) }
}
/** Random search returns a bounded array, with no cursor or text-ranking fields. */
data class RandomSearch(val filter: SearchFilter = SearchFilter(), val size: Int = 100,
    val withExif: Boolean = true) {
    init { require(size in 1..1000) }
}
data class SmartSearch(val query: String, val filter: SearchFilter = SearchFilter(),
    val size: Int = 100, val withExif: Boolean = true) {
    init { require(query.isNotBlank()); require(size in 1..1000) }
}
/** Immich 3.x Context pagination. Never combine page/flat fields with the structured filter. */
data class PagedSmartSearch(val query: String, val page: Int = 1, val size: Int = 100,
    val albumIds: List<String>? = null, val tagIds: List<String>? = null,
    val withExif: Boolean = true, val withDeleted: Boolean = false,
    val personIds: List<String>? = null, val country: String? = null,
    val state: String? = null, val city: String? = null, val type: String? = null) {
    init { require(query.isNotBlank()); require(page >= 1); require(size in 1..1000) }
}
data class SearchAssets(val items: List<ImmichAsset>, val nextCursor: String? = null, val nextPage: String? = null)
data class SearchResult(val assets: SearchAssets)

/** A wrapper distinguishes an omitted patch field from an explicit JSON null (clear). */
data class Change<T>(val value: T?)
data class AssetUpdate(val description: String? = null, val isFavorite: Boolean? = null,
    val dateTimeOriginal: String? = null, val latitude: Double? = null,
    val longitude: Double? = null, val rating: Change<Int>? = null,
    val visibility: String? = null, val livePhotoVideoId: Change<String>? = null) {
    internal fun fields(): Map<String, Any?> = buildMap {
        description?.let { put("description", it) }; isFavorite?.let { put("isFavorite", it) }
        dateTimeOriginal?.let { put("dateTimeOriginal", it) }; latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }; rating?.let { put("rating", it.value) }
        visibility?.let { put("visibility", it) }
        livePhotoVideoId?.let { put("livePhotoVideoId", it.value) }
    }
}
data class AlbumCreate(val albumName: String, val description: String? = null,
    val assetIds: List<String> = emptyList())
data class AlbumUpdate(val albumName: String? = null, val description: Change<String>? = null,
    val albumThumbnailAssetId: String? = null, val isActivityEnabled: Boolean? = null,
    val order: String? = null) {
    internal fun fields(): Map<String, Any?> = buildMap {
        albumName?.let { put("albumName", it) }; description?.let { put("description", it.value) }
        albumThumbnailAssetId?.let { put("albumThumbnailAssetId", it) }
        isActivityEnabled?.let { put("isActivityEnabled", it) }; order?.let { put("order", it) }
    }
}
data class BulkIds(val ids: List<String>)
data class DeleteAssets(val ids: List<String>, val force: Boolean = false)
data class TrashResult(val count: Int)
data class TagUpdate(val name: String? = null, val color: Change<String>? = null) {
    internal fun fields(): Map<String, Any?> = buildMap {
        name?.let { put("name", it) }; color?.let { put("color", it.value) }
    }
}
data class BulkResult(val id: String, val success: Boolean, val error: String? = null,
    val errorMessage: String? = null)
data class UploadCheckItem(val id: String, val checksum: String)
data class UploadCheck(val assets: List<UploadCheckItem>)
data class UploadCheckResult(val id: String, val action: String, val assetId: String? = null,
    val reason: String? = null, val isTrashed: Boolean? = null)
data class UploadChecks(val results: List<UploadCheckResult>)
data class UploadResult(val id: String, val status: String)
data class TagCreate(val name: String, val parentId: String? = null, val color: String? = null)
data class TagsUpsert(val tags: List<String>)
data class AssetMetadata(val key: String, val value: Map<String, Any?>, val updatedAt: String? = null)
data class MetadataUpsert(val items: List<AssetMetadata>)

data class EditAction(val action: String, val parameters: Map<String, Any>) {
    companion object {
        fun crop(x: Int, y: Int, width: Int, height: Int): EditAction {
            require(x >= 0 && y >= 0 && width > 0 && height > 0)
            return EditAction("crop", mapOf("x" to x, "y" to y, "width" to width, "height" to height))
        }
        fun rotate(angle: Int): EditAction {
            require(angle in 0..270)
            return EditAction("rotate", mapOf("angle" to angle))
        }
        fun mirror(horizontal: Boolean) = EditAction("mirror", mapOf("axis" to if (horizontal) "horizontal" else "vertical"))
    }
}
data class AssetEdits(val edits: List<EditAction>)
data class EditsResult(val assetId: String, val edits: List<EditAction>)
