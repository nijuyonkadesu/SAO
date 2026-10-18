package one.njk.sao.extension.immich

import one.njk.sao.source.MediaQuery
import one.njk.sao.source.SourceCategory

/** Home presets belong to the provider, not SAO. */
internal object ImmichHomeCategories {
    val values = listOf(
        SourceCategory("all", "All", MediaQuery(text = "")),
        SourceCategory("happy", "Happy", MediaQuery(text = "happy")),
        SourceCategory("love", "Love", MediaQuery(text = "love")),
        SourceCategory("surprised", "Surprised", MediaQuery(text = "surprised")),
        SourceCategory("cats", "Cats", MediaQuery(text = "cat")),
        SourceCategory("dogs", "Dogs", MediaQuery(text = "dog")),
        SourceCategory("nature", "Nature", MediaQuery(text = "nature landscape")),
        SourceCategory("food", "Food", MediaQuery(text = "food"))
    )
}
