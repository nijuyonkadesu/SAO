package one.njk.sao.database

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsDataStore(private val context: Context) {
    companion object {
        private val Context.rolesDataStore by preferencesDataStore(name = "settings")
        private val NSFW_MODE = booleanPreferencesKey("MODE")
        private val UPLOADS_WIFI_ONLY = booleanPreferencesKey("uploads_wifi_only")
    }

    suspend fun toggleMode(){
        context.rolesDataStore.edit {
            it[NSFW_MODE] = !(it[NSFW_MODE] ?: true)
        }
    }
    fun currentModeFlow(): Flow<Boolean> {
        return context.rolesDataStore.data.map {
            it[NSFW_MODE] ?: true
        }
    }
    fun uploadsWifiOnlyFlow(): Flow<Boolean> = context.rolesDataStore.data.map { it[UPLOADS_WIFI_ONLY] ?: false }
    suspend fun uploadsWifiOnly(value: Boolean) { context.rolesDataStore.edit { it[UPLOADS_WIFI_ONLY]=value } }
}
