package one.njk.sao.floating

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import one.njk.sao.R
import one.njk.sao.rays.RaysClient

internal fun Context.addMediaSource() {
    val rays = RaysClient.packages.filter { RaysClient.installed(this, it) }
    if (rays.isEmpty()) { startActivity(ImmichSettingsActivity.intent(this)); return }
    val labels = listOf(getString(R.string.source_immich)) + rays.map {
        getString(if (it == RaysClient.DEBUG_PACKAGE) R.string.source_rays_debug else R.string.source_rays)
    }
    MaterialAlertDialogBuilder(this).setTitle(R.string.source_add)
        .setItems(labels.toTypedArray()) { _, index ->
            startActivity(if (index == 0) ImmichSettingsActivity.intent(this)
                else RaysSettingsActivity.intent(this, packageName = rays[index - 1]))
        }.show()
}

internal fun Context.editMediaSource(profile: SourceProfile?) {
    when (profile?.configuration) {
        is ImmichConfig -> startActivity(ImmichSettingsActivity.intent(this, profile.id))
        is RaysConfig -> startActivity(RaysSettingsActivity.intent(this, profile.id))
        else -> android.widget.Toast.makeText(this, R.string.source_unavailable, android.widget.Toast.LENGTH_SHORT).show()
    }
}
