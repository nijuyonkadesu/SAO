package one.njk.sao.floating

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject

class ImmichSourceFactory @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers) : MediaSourceFactory {
    override val advancedSearch = true
    override val randomBrowse = true
    override fun create(profile: SourceProfile) = ImmichMediaSource(context,
        profile.configuration as ImmichConfig, profile.identity, dispatchers)
}

class ImmichConfigurationCodec @Inject constructor() : SourceConfigurationCodec {
    override fun encode(configuration: SourceConfiguration) = (configuration as ImmichConfig).let {
        JSONObject().put("url", it.serverUrl).put("key", it.apiKey)
            .put("smart", it.smartSearch).put("album", it.albumId.orEmpty())
    }
    override fun decode(json: JSONObject) = ImmichConfig(json.getString("url"), json.getString("key"),
        json.optBoolean("smart", true), json.optString("album").takeIf { it.isNotBlank() })
}
