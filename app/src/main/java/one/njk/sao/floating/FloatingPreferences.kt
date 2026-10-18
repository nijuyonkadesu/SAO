package one.njk.sao.floating

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** One-time cleanup of the obsolete UI keys from earlier prototypes. No browsing state is read or written. */
private object RemovePickerUiPreferences : DataMigration<Preferences> {
    private val obsolete = listOf("browse_media_id", "browse_query", "browse_filter").map(::stringPreferencesKey)
    override suspend fun shouldMigrate(currentData: Preferences) = obsolete.any { currentData.contains(it) }
    override suspend fun migrate(currentData: Preferences) = currentData.toMutablePreferences().apply {
        obsolete.forEach { remove(it) }
    }
    override suspend fun cleanUp() = Unit
}
private val Context.floatingDataStore by preferencesDataStore("floating_picker",
    produceMigrations = { listOf(RemovePickerUiPreferences) })

data class FloatingOptions(val preferStickers: Boolean = true, val pasteAssistance: Boolean = false,
    val edgeEnd: Boolean = true, val verticalPosition: Float = .35f,
    val autoHide: Boolean = true, val idleSeconds: Int = 3, val telegramPackages: Set<String> = emptySet(),
    val browseSequence: Boolean = false, val longPressMs: Int = DEFAULT_LONG_PRESS_MS,
    val showOnLaunch: Boolean = true, val showEmojiShortcuts: Boolean = false,
    val emojiShortcuts: String = EmojiShortcuts.DEFAULT) {
    companion object {
        const val DEFAULT_LONG_PRESS_MS = 160
        const val MIN_LONG_PRESS_MS = 100
        const val MAX_LONG_PRESS_MS = 1000
    }
}

@Singleton
class FloatingPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val stickers = booleanPreferencesKey("prefer_stickers")
    private val paste = booleanPreferencesKey("paste_assistance")
    private val edge = booleanPreferencesKey("edge_end")
    private val position = floatPreferencesKey("vertical_position")
    private val hide = booleanPreferencesKey("auto_hide")
    private val idle = intPreferencesKey("idle_seconds")
    private val telegram = stringSetPreferencesKey("telegram_packages")
    private val sequence = booleanPreferencesKey("browse_sequence")
    private val hold = intPreferencesKey("long_press_ms")
    private val launch = booleanPreferencesKey("show_on_launch")
    private val emojiRow = booleanPreferencesKey("show_emoji_shortcuts")
    private val emojiList = stringPreferencesKey("emoji_shortcuts")
    val options = context.floatingDataStore.data.map {
        FloatingOptions(it[stickers] ?: true, it[paste] ?: false, it[edge] ?: true, it[position] ?: .35f,
            it[hide] ?: true, (it[idle] ?: 3).coerceIn(3, 10), it[telegram].orEmpty(), it[sequence] ?: false,
            (it[hold] ?: FloatingOptions.DEFAULT_LONG_PRESS_MS).coerceIn(FloatingOptions.MIN_LONG_PRESS_MS, FloatingOptions.MAX_LONG_PRESS_MS),
            it[launch] ?: true, it[emojiRow] ?: false, it[emojiList] ?: EmojiShortcuts.DEFAULT)
    }
    suspend fun showEmojiShortcuts(value: Boolean) { context.floatingDataStore.edit { it[emojiRow]=value } }
    suspend fun emojiShortcuts(value: String) {
        val normalized=EmojiShortcuts.parse(value).joinToString(" ")
        context.floatingDataStore.edit { it[emojiList]=normalized }
    }
    suspend fun current() = options.first()
    suspend fun preferStickers(value: Boolean) { context.floatingDataStore.edit { it[stickers] = value } }
    suspend fun pasteAssistance(value: Boolean) { context.floatingDataStore.edit { it[paste] = value } }
    suspend fun autoHide(value: Boolean) { context.floatingDataStore.edit { it[hide] = value } }
    suspend fun showOnLaunch(value: Boolean) { context.floatingDataStore.edit { it[launch] = value } }
    suspend fun idleSeconds(value: Int) { context.floatingDataStore.edit { it[idle] = value.coerceIn(3, 10) } }
    suspend fun browseSequence(value: Boolean) { context.floatingDataStore.edit { it[sequence] = value } }
    suspend fun longPressMs(value: Int) { context.floatingDataStore.edit {
        it[hold] = value.coerceIn(FloatingOptions.MIN_LONG_PRESS_MS, FloatingOptions.MAX_LONG_PRESS_MS)
    } }
    suspend fun telegramPackages(value: Set<String>) {
        context.floatingDataStore.edit { it[telegram] = value.filter(PackageNames::valid).toSet() }
    }
    suspend fun bubblePosition(end: Boolean, fraction: Float) {
        context.floatingDataStore.edit { it[edge] = end; it[position] = fraction.coerceIn(0f, 1f) }
    }
}
