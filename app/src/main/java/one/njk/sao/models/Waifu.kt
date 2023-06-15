package one.njk.sao.models

import com.squareup.moshi.Json
import java.util.UUID

data class Waifu(
    @Json(name = "url")val url: String,
) {
    val id: UUID = UUID.randomUUID()
}

data class Waifus(
    @Json(name = "files") val files: List<String>,
)
// TODO: See if @FromJson of moshi is any better than kotlin map