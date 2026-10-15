package one.njk.sao.immich

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*
import retrofit2.Call

/** Relative routes preserve reverse-proxy prefixes. Mutation methods are never invoked by browsing. */
interface ImmichApi {
    @GET("server/version") suspend fun version(): ServerVersion
    @GET("server/features") suspend fun features(): ServerFeatures
    @GET("api-keys/me") suspend fun apiKey(): ApiKeyInfo
    @POST("search/metadata") suspend fun search(@Body query: MetadataSearch): SearchResult
    @POST("search/random") suspend fun randomSearch(@Body query: RandomSearch): List<ImmichAsset>
    @POST("search/smart") suspend fun smartSearch(@Body query: SmartSearch): SearchResult
    @POST("search/smart") suspend fun pagedSmartSearch(@Body query: PagedSmartSearch): SearchResult
    @GET("people") suspend fun people(@Query("page") page: Int = 1, @Query("size") size: Int = 40): ImmichPeople
    @GET("search/person") suspend fun searchPeople(@Query("name") name: String): List<ImmichPerson>
    @Streaming @GET("people/{id}/thumbnail") suspend fun personThumbnail(@Path("id") id: String): ResponseBody
    @GET("search/suggestions") suspend fun places(@Query("type") type: String,
        @Query("country") country: String? = null, @Query("state") state: String? = null): List<String?>
    @GET("assets/{id}") suspend fun asset(@Path("id") id: String): ImmichAsset
    // Same ordering/scope as the Photos web timeline. Bucket bodies are streamed, not buffered.
    @GET("timeline/buckets") suspend fun timeBuckets(@Query("albumId") albumId: String? = null,
        @Query("order") order: String = "desc", @Query("orderBy") orderBy: String = "takenAt",
        @Query("visibility") visibility: String = "timeline", @Query("withStacked") withStacked: Boolean = true,
        @Query("withPartners") withPartners: Boolean = true): List<TimeBucket>
    @Streaming @GET("timeline/bucket") fun timeBucket(@Query("timeBucket") bucket: String,
        @Query("albumId") albumId: String? = null, @Query("order") order: String = "desc",
        @Query("orderBy") orderBy: String = "takenAt", @Query("visibility") visibility: String = "timeline",
        @Query("withStacked") withStacked: Boolean = true, @Query("withPartners") withPartners: Boolean = true): Call<ResponseBody>
    @PUT("assets/{id}") suspend fun updateAssetFields(@Path("id") id: String, @Body fields: RequestBody): ImmichAsset
    @POST("assets/bulk-upload-check") suspend fun checkUploads(@Body checks: UploadCheck): UploadChecks
    @HTTP(method = "DELETE", path = "assets", hasBody = true)
    suspend fun deleteAssets(@Body assets: DeleteAssets)
    @POST("trash/restore/assets") suspend fun restoreAssets(@Body assets: BulkIds): TrashResult
    @Multipart @POST("assets") suspend fun upload(@Part assetData: MultipartBody.Part,
        @Part("fileCreatedAt") created: RequestBody, @Part("fileModifiedAt") modified: RequestBody): UploadResult
    @Streaming @GET("assets/{id}/original") suspend fun original(@Path("id") id: String,
        @Query("edited") edited: Boolean = true): ResponseBody
    @Streaming @GET("assets/{id}/thumbnail") suspend fun thumbnail(@Path("id") id: String,
        @Query("size") size: String = "preview", @Query("edited") edited: Boolean = true): ResponseBody
    @GET("assets/{id}/edits") suspend fun edits(@Path("id") id: String): EditsResult
    @PUT("assets/{id}/edits") suspend fun setEdits(@Path("id") id: String, @Body edits: AssetEdits): EditsResult
    @DELETE("assets/{id}/edits") suspend fun resetEdits(@Path("id") id: String)
    @GET("assets/{id}/metadata") suspend fun metadata(@Path("id") id: String): List<AssetMetadata>
    @PUT("assets/{id}/metadata") suspend fun setMetadata(@Path("id") id: String, @Body metadata: MetadataUpsert): List<AssetMetadata>
    @GET("albums") suspend fun albums(@Query("isOwned") owned: Boolean? = null,
        @Query("isShared") shared: Boolean? = null): List<ImmichAlbum>
    @GET("albums/{id}") suspend fun album(@Path("id") id: String): ImmichAlbum
    @POST("albums") suspend fun createAlbum(@Body album: AlbumCreate): ImmichAlbum
    @PATCH("albums/{id}") suspend fun updateAlbumFields(@Path("id") id: String, @Body fields: RequestBody): ImmichAlbum
    @DELETE("albums/{id}") suspend fun deleteAlbum(@Path("id") id: String)
    @PUT("albums/{id}/assets") suspend fun addToAlbum(@Path("id") id: String, @Body assets: BulkIds): List<BulkResult>
    @HTTP(method = "DELETE", path = "albums/{id}/assets", hasBody = true)
    suspend fun removeFromAlbum(@Path("id") id: String, @Body assets: BulkIds): List<BulkResult>
    @GET("tags") suspend fun tags(): List<ImmichTag>
    @POST("tags") suspend fun createTag(@Body tag: TagCreate): ImmichTag
    @PUT("tags") suspend fun upsertTags(@Body tags: TagsUpsert): List<ImmichTag>
    @GET("tags/{id}") suspend fun tag(@Path("id") id: String): ImmichTag
    @PUT("tags/{id}") suspend fun updateTagFields(@Path("id") id: String, @Body fields: RequestBody): ImmichTag
    @DELETE("tags/{id}") suspend fun deleteTag(@Path("id") id: String)
    @PUT("tags/{id}/assets") suspend fun tagAssets(@Path("id") id: String, @Body assets: BulkIds): List<BulkResult>
    @HTTP(method = "DELETE", path = "tags/{id}/assets", hasBody = true)
    suspend fun untagAssets(@Path("id") id: String, @Body assets: BulkIds): List<BulkResult>
}
