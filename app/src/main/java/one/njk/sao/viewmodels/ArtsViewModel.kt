package one.njk.sao.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import one.njk.sao.models.ConfigState
import one.njk.sao.models.Waifu
import one.njk.sao.network.WaifuApiService
import javax.inject.Inject

enum class CategoryType { SFW, NSFW }
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
val nsfwCategories = listOf("waifu", "neko", "trap", "blowjob")

@HiltViewModel
class ArtsViewModel @Inject constructor(
    private val waifuApiService: WaifuApiService,
    val imageLoader: ImageLoader
) : ViewModel() {
    // TODO: 0 handle network disconnect crash
    // TODO: 3 Survive process deaths (using savedstate handle & lifecycle methods)
    // TODO: 4 write Proguard (for type / class preservation)
    private val waifus = mutableListOf<Waifu>()

    private val _availableCategories = MutableLiveData(sfwCategories)
    val availableCategories: LiveData<List<String>> = _availableCategories

    // Type & Category state
    val configState = MutableStateFlow(
        ConfigState(CategoryType.SFW, "waifu"))

    // Previously chosen values
    private var _previousType = CategoryType.SFW
    private var _previousCategory = "waifu"

    val displayType
    get() = configState.map {
        it.type.uppercase()
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val waifuList: LiveData<List<Waifu>> = configState.flatMapLatest { state ->

        cleanListIfNeeded(state)

        Log.d("url", "${state.type}, ${state.category}")
        val freshWaifus = waifuApiService.getWaifus(
            type = state.type,
            category = state.category,
            exclude = listOf("nsfw")
        ).files.map { Waifu(it) }
        waifus.addAll(freshWaifus)
        // technically, the files you've seen already can be send here as exclude,
        // so that you'll never see those files again... but... ah, it grows too big soon

        flowOf(waifus.toList()).flowOn(Dispatchers.Default).conflate()
    }.asLiveData()

    fun updateType(category: String) {
        configState.value = configState.value.copy(
            category = category
        )
    }
    fun toggleType(){
        val categoryType = when(configState.value.categoryType){
            CategoryType.SFW -> {
                _availableCategories.value = nsfwCategories
                CategoryType.NSFW
            }
            CategoryType.NSFW -> {
                _availableCategories.value = sfwCategories
                CategoryType.SFW
            }
        }
        configState.value = configState.value.copy(
            categoryType = categoryType
        )
    }

    /**
     * Compare previously chosen type & category to decide whether to clear
     * [waifus] list or not
     */
    private fun cleanListIfNeeded(state: ConfigState) {
        if(_previousCategory != state.category) {
            _previousType = state.categoryType
            _previousCategory = state.category
            waifus.clear()
        } else if(_previousType != state.categoryType) {
            _previousType = state.categoryType
            waifus.clear()
        }
    }
}
