package one.njk.sao.data

import one.njk.sao.models.Waifu

interface WaifuApiRepository {
    suspend fun getWaifus(
        type: String,
        category: String,
        exclude: List<String>
    ): ApiResult<List<Waifu>>
}