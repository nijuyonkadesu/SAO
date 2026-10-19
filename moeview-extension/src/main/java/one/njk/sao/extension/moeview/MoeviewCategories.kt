package one.njk.sao.extension.moeview

import one.njk.sao.moeview.data.Board
import one.njk.sao.source.*

/** Reviewed vocabulary, not waifu.pics endpoint names. See docs/moeview-category-tags.md. */
internal object MoeviewCategories {
    private val shared=listOf(
        "School uniform" to "school_uniform", "Blush" to "blush", "Ponytail" to "ponytail",
        "Waifu" to "1girl", "Neko" to "cat_ears", "Shinobu" to "oshino_shinobu", "Megumin" to "megumin",
        "Bully" to "bullying", "Cuddle" to "cuddling", "Cry" to "crying", "Hug" to "hug",
        "Kiss" to "kiss", "Lick" to "licking", "Pat" to "headpat", "Smug" to "smug",
        "Yeet" to "throwing", "Smile" to "smile", "Wave" to "waving", "Handhold" to "holding_hands",
        "Nom" to "eating", "Bite" to "biting", "Glomp" to "glomp", "Slap" to "slapping",
        "Kick" to "kicking", "Happy" to "happy", "Wink" to "one_eye_closed", "Poke" to "poking",
        "Dance" to "dancing", "Wince" to "wince")
    private val boards=mapOf(
        // Yande.re removes expression/action tags. Some familiar names instead identify
        // artists/circles/characters: e.g. ponytail, hug, happy. Never substitute those.
        Board.YANDERE to listOf("School uniform" to "seifuku", "Neko" to "nekomimi",
            "Shinobu" to "oshino_shinobu", "Megumin" to "megumin"),
        Board.KONACHAN to listOf("School uniform" to "school_uniform", "Blush" to "blush", "Ponytail" to "ponytail",
            "Neko" to "catgirl", "Shinobu" to "oshino_shinobu", "Megumin" to "megumin",
            "Cry" to "crying", "Hug" to "hug", "Kiss" to "kiss", "Cat smile" to "cat_smile",
            "Kick" to "kick", "Wink" to "wink"),
        Board.GELBOORU to shared+listOf("Howling" to "howling", "Bonk" to "bonk", "High five" to "high_five", "Kill" to "murder"),
        // Danbooru uses the same modern general tags for these concepts. The published
        // vocabulary confirms these choices; live API verification is Cloudflare-blocked.
        Board.DANBOORU to shared+listOf("Head bump" to "head_bump", "Death" to "death"))

    fun forBoard(board: Board): List<SourceCategory> = (listOf("All" to "")+boards.getValue(board)).map { (label,tag) ->
        SourceCategory(tag.ifEmpty { "all" },label,MediaQuery(text=tag,mode=SearchMode.DESCRIPTION))
    }
}
