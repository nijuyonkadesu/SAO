package one.njk.sao.usage

import one.njk.sao.floating.MessagingTarget
import one.njk.sao.source.MediaItem
import one.njk.sao.source.SearchMode

fun MessagingTarget.usageTarget() = when(this) {
    MessagingTarget.WHATSAPP,MessagingTarget.WHATSAPP_BUSINESS -> UsageTarget.WHATSAPP
    MessagingTarget.TELEGRAM -> UsageTarget.TELEGRAM
    MessagingTarget.DISCORD -> UsageTarget.DISCORD
    MessagingTarget.INSTAGRAM -> UsageTarget.INSTAGRAM
    MessagingTarget.OTHER -> UsageTarget.OTHER
}
fun mediaUsage(action: UsageAction,surface: UsageSurface,item: MediaItem,target: UsageTarget = UsageTarget.NONE,
    route: UsageRoute = UsageRoute.NONE,outcome: UsageOutcome = UsageOutcome.ACCEPTED) =
    UsageEvent(action,surface,item.remote?.sourceId.orEmpty(),UsageEvent.media(item),target,route,outcome)

fun SearchMode.usageMode() = when(this) {
    SearchMode.CONTEXT -> UsageSearchMode.CONTEXT
    SearchMode.FILENAME -> UsageSearchMode.FILENAME
    SearchMode.DESCRIPTION -> UsageSearchMode.DESCRIPTION
    SearchMode.PATH -> UsageSearchMode.PATH
    SearchMode.OCR -> UsageSearchMode.OCR
}
