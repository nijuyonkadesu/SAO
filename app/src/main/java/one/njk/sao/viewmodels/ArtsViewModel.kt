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
    // TODO: 1. Paging (auto fetching)
    // TODO: 2 Switch Sources when category and type changes
    // TODO: 2. !! HOW TO TRACK WHICH IMAGE I'M IN !!!

    val waifuList = flow {
        val waifus = mutableListOf<Waifu>()

        waifuApiService.getWaifus(
            type = "sfw",
            category = "waifu",
            exclude = listOf("nsfw")
        ).files.map { waifus.add(Waifu(it)) }

        emit(waifus)
    }
}
