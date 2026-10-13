package one.njk.sao.floating

import android.os.Bundle

/** Android saved instance state only; no preferences or files for browsing controls. */
internal object SearchQueryState {
    fun encode(value: SearchConstraints) = Bundle().apply {
        putString("source", value.sourceId); putString("country", value.country)
        putString("state", value.state); putString("city", value.city); putString("media", value.media.name)
        putStringArrayList("people_ids", ArrayList(value.people.map { it.id }))
        putStringArrayList("people_names", ArrayList(value.people.map { it.name }))
    }
    fun decode(bundle: Bundle?): SearchConstraints {
        if (bundle == null) return SearchConstraints()
        val names = bundle.getStringArrayList("people_names").orEmpty()
        return SearchConstraints(bundle.getString("source"), bundle.getStringArrayList("people_ids").orEmpty()
            .mapIndexed { index, id -> SearchPerson(id, names.getOrNull(index).orEmpty(), bundle.getString("source")) },
            bundle.getString("country"), bundle.getString("state"), bundle.getString("city"),
            SearchMedia.entries.firstOrNull { it.name == bundle.getString("media") } ?: SearchMedia.ALL)
    }
}
