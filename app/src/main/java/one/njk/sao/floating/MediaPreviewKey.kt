package one.njk.sao.floating

import one.njk.sao.source.*

internal fun previewMemoryKey(item: MediaItem)=buildString {
        append("sao-preview:")
        val fields=listOf(item.identityKey.encode(),item.id,item.asset,item.mimeType,item.animated.toString(),item.remote?.edited.toString())+
            item.remote?.cacheKeys.orEmpty().toSortedMap().flatMap { (key,value) -> listOf(key,value) }
        fields.forEach { append(it.length); append(':'); append(it) }
    }
