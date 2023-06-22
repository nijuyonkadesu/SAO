package one.njk.sao.viewmodels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.flow
import one.njk.sao.models.Waifu
import one.njk.sao.network.WaifuApiService
import javax.inject.Inject

@HiltViewModel
class ArtsViewModel @Inject constructor(
    private val waifuApiService: WaifuApiService
) : ViewModel() {
    // TODO: 0 handle network disconnect crash
    // TODO: 2 Switch Sources when category and type changes
    // TODO: 3 Survive process deaths (using savedstate handle & lifecycle methods)
    // TODO: 4 write Proguard (for type / class preservation)
    private val waifus = mutableListOf<Waifu>()

    val waifuList = flow {
        waifuApiService.getWaifus(
            type = "sfw",
            category = "waifu",
            exclude = listOf("nsfw")
        ).files.map { waifus.add(Waifu(it)) }
        // technically, the files you've seen already can be send here as exclude,
        // so that you'll never see those files again... but... ah, it grows too big soon

        emit(waifus)
    }

    // TODO: Figure out a way for UI to refresh on changing categories itself
    val sfwCategories = listOf(
        "waifu",
        "neko",
        "shinobu",
        "megumin",
        "bully",
        "cuddle",
        "cry",
        "hug",
        "awoo",
        "kiss",
        "lick",
        "pat",
        "smug",
        "bonk",
        "yeet",
        "blush",
        "smile",
        "wave",
        "highfive",
        "handhold",
        "nom",
        "bite",
        "glomp",
        "slap",
        "kill",
        "kick",
        "happy",
        "wink",
        "poke",
        "dance",
        "cringe"
    )
}
