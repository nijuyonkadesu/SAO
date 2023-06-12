package one.njk.sao.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.flow
import one.njk.sao.models.Waifu

class ArtsViewModel: ViewModel() {
    // TODO: 1. Build a retrofit client with different sfw point
    // TODO: 2. Add Small text in carosuel (and try to make it vertical ?)
    // TODO: 3. !! HOW TO TRACK WHICH IMAGE I'M IN !!!

    val waifuList = flow { emit(listOf(
        Waifu("1", "https://i.waifu.pics/Tj6Wzwo.png"),
        Waifu("2", "https://i.waifu.pics/ZBCHHyT.jpg"),
        Waifu("3", "https://i.waifu.pics/04afe1y.jpg"),
        Waifu("4", "https://i.waifu.pics/qnlMuuF.gif")
    )) }
}
