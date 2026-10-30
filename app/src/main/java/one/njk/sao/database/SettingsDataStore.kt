package one.njk.sao.database

import android.content.Context
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsDataStore(private val context: Context) {
    companion object {
        private val Context.rolesDataStore by preferencesDataStore(name = "settings")
        private val NSFW_MODE = booleanPreferencesKey("MODE")
        private val SOURCE_APPROVALS = stringSetPreferencesKey("source_extension_approvals")
        private val UPLOADS_WIFI_ONLY = booleanPreferencesKey("uploads_wifi_only")
        private val EXTENSION_REPOSITORIES = stringSetPreferencesKey("extension_repositories")
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
    fun extensionRepositoriesFlow(): Flow<Set<String>?> = context.rolesDataStore.data.map { it[EXTENSION_REPOSITORIES] }
    suspend fun extensionRepository(url: String, add: Boolean, defaults: Set<String>) { context.rolesDataStore.edit {
        val current=it[EXTENSION_REPOSITORIES] ?: defaults
        it[EXTENSION_REPOSITORIES]=if(add) current+url else current-url
    } }
    fun extensionApprovalsFlow(): Flow<Set<String>> = context.rolesDataStore.data.map { it[SOURCE_APPROVALS].orEmpty() }
    suspend fun extensionApproval(packageName: String, signer: String?) { context.rolesDataStore.edit {
        val remaining=it[SOURCE_APPROVALS].orEmpty().filterNot { entry -> entry.startsWith("$packageName|") }.toSet()
        it[SOURCE_APPROVALS]=if(signer==null) remaining else remaining+"$packageName|$signer"
    } }

}
