package one.njk.sao.network

import one.njk.sao.models.Waifu
import one.njk.sao.models.Waifus
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path


interface WaifuApiService {
    @GET("/{type}/{category}")
    suspend fun getWaifu(
        @Path("type") type: String,
        @Path("category") category: String,
    ): Waifu

    // To simulate batch response from waifu.pics, use postman
    // create a post request > go to body > form-data and type exclude = value
    // this form-data is converted to binary, it's equivalent is MultiPart
    @Multipart
    @POST("/many/{type}/{category}")
    suspend fun getWaifus(
        @Path("type") type: String,
        @Path("category") category: String,
        @Part("exclude") exclude: List<String>
    ): Waifus
}