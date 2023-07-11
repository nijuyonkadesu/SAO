package one.njk.sao.models

import one.njk.sao.viewmodels.CategoryType

data class ConfigState(
    val categoryType: CategoryType,
    val category: String,
    val page: Int
){
    val type
    get() = when(categoryType){
        CategoryType.SFW -> "sfw"
        CategoryType.NSFW -> "nsfw"
    }
}
