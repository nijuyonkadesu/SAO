package one.njk.sao.database

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsDataStore(private val context: Context) {
    companion object {
        private val Context.rolesDataStore by preferencesDataStore(name = "settings")
        private val NSFW_MODE = booleanPreferencesKey("MODE")
    }

    suspend fun toggleMode(){
        context.rolesDataStore.edit {
            it[NSFW_MODE] = !currentMode()
        }
    }
    private suspend fun currentMode(): Boolean {
        return context.rolesDataStore.data.first()[NSFW_MODE] ?: true
    }
    fun currentModeFlow(): Flow<Boolean> {
        return context.rolesDataStore.data.map {
            it[NSFW_MODE] ?: true
        }
    }
}
