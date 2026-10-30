package one.njk.sao.floating

import android.content.Context
import one.njk.sao.R
import one.njk.sao.source.SourceProfile

/** The synthetic All label is UI text; names belonging to users/providers are data. */
internal fun sourceDisplayName(context: Context, id: String?, name: String): String =
    if (id == ALL_SOURCE_ID) context.getString(R.string.source_all_name) else name

internal fun SourceProfile.displayName(context: Context) = sourceDisplayName(context, id, name)
