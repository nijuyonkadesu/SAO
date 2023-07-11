package one.njk.sao.viewmodels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import one.njk.sao.BuildConfig
import one.njk.sao.data.WaifuApiRepository
import one.njk.sao.models.ConfigState
import one.njk.sao.models.Waifu
import java.util.concurrent.atomic.AtomicBoolean
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
    private val waifuRepository: WaifuApiRepository,
    val imageLoader: ImageLoader
) : ViewModel() {
    // TODO: 3 Survive process deaths (using savedstate handle & lifecycle methods)
    // TODO: 4 write Proguard (for type / class preservation)
    private val waifus = mutableListOf<Waifu>()

    private val _availableCategories = MutableLiveData(sfwCategories)
    val availableCategories: LiveData<List<String>> = _availableCategories

    // Type & Category state
    val configState = MutableStateFlow(
        ConfigState(CategoryType.SFW, "waifu", 1))

    // Previously chosen values
    private var _previousType = CategoryType.SFW
    private var _previousCategory = "waifu"

    val displayType
    get() = configState.map {
        it.type.uppercase()
    }.asLiveData()

    private var broJustNowICalled = AtomicBoolean(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val waifuList: LiveData<List<Waifu>> = configState.flatMapLatest { state ->

        cleanListIfNeeded(state)

        val freshWaifus = waifuRepository.getWaifus(state.type, state.category, listOf("nsfw")).data
        if(BuildConfig.DEBUG)
            Log.d("url", "${state.type}, ${state.category}, items: ${freshWaifus?.size}")

        freshWaifus?.let {
            waifus.addAll(it)
        }
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

    fun getNextSetOfWaifus(){
        if(!broJustNowICalled.get()){
            broJustNowICalled.set(true)
            configState.value = configState.value.copy(page = configState.value.page + 1)
            if(BuildConfig.DEBUG)
                Log.d("url", "Requesting New Waifus!")
            calmDownBro()
        }
    }
    private fun calmDownBro(){
        viewModelScope.launch {
            delay(3000)
            broJustNowICalled.set(false)
        }
    }
}
