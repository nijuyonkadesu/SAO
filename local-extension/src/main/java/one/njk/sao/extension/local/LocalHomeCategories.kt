package one.njk.sao.extension.local

import one.njk.sao.source.MediaQuery
import one.njk.sao.source.SourceCategory

/** Home presets belong to the provider, not SAO. */
internal object LocalHomeCategories {
    val values = listOf(
        SourceCategory("all", "All", MediaQuery(text = "")),
        SourceCategory("happy", "Happy", MediaQuery(text = "happy")),
        SourceCategory("love", "Love", MediaQuery(text = "love")),
        SourceCategory("surprised", "Surprised", MediaQuery(text = "surprised")),
        SourceCategory("cats", "Cats", MediaQuery(text = "cat")),
        SourceCategory("dogs", "Dogs", MediaQuery(text = "dog")),
        SourceCategory("anime", "Anime", MediaQuery(text = "anime")),
        SourceCategory("dance", "Dance", MediaQuery(text = "dancing"))
    )
}
