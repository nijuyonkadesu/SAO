package one.njk.sao.floating

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.rays.RaysClient

@EntryPoint @InstallIn(SingletonComponent::class)
interface SourceExtensionAccess { fun extensions(): SourceExtensions; fun sources(): SourceProfiles }
private fun Context.extensions() = EntryPointAccessors.fromApplication(applicationContext,SourceExtensionAccess::class.java).extensions()

internal fun Context.addMediaSource() {
    val activity = this as? AppCompatActivity ?: return
    activity.lifecycleScope.launch {
        val installed = extensions().installed()
        val rays = RaysClient.packages.filter { RaysClient.installed(this@addMediaSource,it) }
        val labels = installed.map { it.name } + rays.map { getString(if (it == RaysClient.DEBUG_PACKAGE) R.string.source_rays_debug else R.string.source_rays) }
        if (labels.isEmpty()) {
            MaterialAlertDialogBuilder(activity).setTitle(R.string.source_add).setMessage(R.string.source_extension_install).setPositiveButton(android.R.string.ok,null).show(); return@launch
        }
        fun open(index: Int) {
            startActivity(if (index < installed.size) SourceSetupActivity.intent(activity,installed[index])
                else RaysSettingsActivity.intent(activity,packageName = rays[index-installed.size]))
        }
        if (labels.size == 1) open(0) else MaterialAlertDialogBuilder(activity).setTitle(R.string.source_add)
            .setItems(labels.toTypedArray()) { _, index -> open(index) }.show()
    }
}
internal fun Context.editMediaSource(profile: one.njk.sao.source.SourceProfile?) {
    val activity = this as? AppCompatActivity ?: return
    when (val config = profile?.configuration) {
        is ExtensionConfig -> activity.lifecycleScope.launch {
            try { startActivity(SourceSetupActivity.intent(activity,extensions().requireInstalled(config),config.profile)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { unavailableSource(profile) }
        }
        is RaysConfig -> startActivity(RaysSettingsActivity.intent(this,profile.id))
        else -> if (profile != null) activity.unavailableSource(profile)
    }
}

private fun AppCompatActivity.unavailableSource(profile: one.njk.sao.source.SourceProfile) {
    MaterialAlertDialogBuilder(this).setTitle(profile.name).setMessage(R.string.source_extension_missing)
        .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.source_delete) { _,_ ->
            lifecycleScope.launch {
                EntryPointAccessors.fromApplication(applicationContext,SourceExtensionAccess::class.java).sources().remove(profile.id)
            }
        }.show()
}
