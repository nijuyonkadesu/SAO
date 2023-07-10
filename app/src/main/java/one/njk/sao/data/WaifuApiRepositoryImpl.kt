package one.njk.sao.data

import android.util.Log
import one.njk.sao.BuildConfig
import one.njk.sao.models.Waifu
import one.njk.sao.network.WaifuApiService
import retrofit2.HttpException

class WaifuApiRepositoryImpl(
    private val api: WaifuApiService
): WaifuApiRepository {
    override suspend fun getWaifus(
        type: String,
        category: String,
        exclude: List<String>
    ): ApiResult<List<Waifu>> {

        return try {
            val freshWaifus = api.getWaifus(
                type = type,
                category = category,
                exclude = exclude
            ).files.map{ Waifu(it) }
            ApiResult.Success(freshWaifus)

        } catch(e: HttpException){
            if(BuildConfig.DEBUG)
                Log.d("network", e.message.toString())
            ApiResult.Failure(null)

        } catch (e: Exception){
            if(BuildConfig.DEBUG)
                Log.d("network", "Fatal ${e.message}")
            ApiResult.Failure(null)
        }
    }
}