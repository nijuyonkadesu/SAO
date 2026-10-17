package one.njk.sao.floating.emoji

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.njk.sao.floating.FloatingDispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmojiKeywords @Inject constructor(@ApplicationContext private val context: Context, private val dispatchers: FloatingDispatchers) {
    private val loading = Mutex()
    private var index: EmojiKeywordIndex? = null

    internal suspend fun suggest(keyword: String): List<EmojiSuggestion> {
        val dictionary = loading.withLock {
            index ?: run {
                val entries = withContext(dispatchers.io) { EmojiKeywordIndex.read(context.assets.open("emoji/keywords-en.tsv").reader(Charsets.UTF_8)) }
                withContext(dispatchers.cpu) { EmojiKeywordIndex(entries) }.also { index = it }
            }
        }
        return withContext(dispatchers.cpu) { dictionary.suggest(keyword) }
    }
}
