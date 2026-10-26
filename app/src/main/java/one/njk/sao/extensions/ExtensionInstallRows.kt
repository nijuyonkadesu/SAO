package one.njk.sao.extensions

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import one.njk.sao.R
import one.njk.sao.databinding.ItemInstalledExtensionBinding
import one.njk.sao.floating.InstalledExtensionApp
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.source.settings.R as SettingsR

internal fun extensionIconPlaceholder(view: android.view.View): android.graphics.drawable.Drawable =
    requireNotNull(androidx.appcompat.content.res.AppCompatResources.getDrawable(view.context,R.drawable.ic_picker_source)).mutate().apply {
        setTint(com.google.android.material.color.MaterialColors.getColor(view,com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

internal enum class CatalogAction { INSTALL, UPDATE, CURRENT, NEWER, INCOMPATIBLE, DIFFERENT_PUBLISHER }
internal fun CatalogExtension.action(installedVersion: Long?,installedSigner: String?,sdk: Int,abis: List<String>): CatalogAction = when {
    installedVersion!=null && installedSigner!=signerSha256 -> CatalogAction.DIFFERENT_PUBLISHER
    apiVersion!=ExtensionProtocol.VERSION || sdk<minSdk || artifact(abis)==null -> CatalogAction.INCOMPATIBLE
    installedVersion==null -> CatalogAction.INSTALL
    installedVersion<versionCode -> CatalogAction.UPDATE
    installedVersion==versionCode -> CatalogAction.CURRENT
    else -> CatalogAction.NEWER
}

/** A card observes only its own transfer, not every catalog/other package update. */
internal data class InstallRowState(val active: Boolean=false,val phase: InstallPhase?=null,val progress: Int=0,
    val busy: Boolean=false,val failure: Int?=null)
internal fun CatalogState.installRow(packageName: String): InstallRowState {
    val active=activePackage==packageName
    return InstallRowState(active,phase.takeIf { active },if(active) progress else 0,activePackage!=null,failures[packageName])
}

internal fun bindInstall(binding: ItemInstalledExtensionBinding,entry: CatalogExtension?,installed: InstalledExtensionApp?,
    state: InstallRowState,interactive: Boolean,install: (CatalogExtension)->Unit,cancel: ()->Unit) {
    val context=binding.root.context
    val artifact=entry?.artifact(Build.SUPPORTED_ABIS.toList())
    bindDownloadLink(binding.root,artifact?.url)
    val active=entry!=null && state.active
    val action=entry?.action(installed?.versionCode,installed?.signer,Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS.toList())
    val failure=state.failure
    binding.catalogDescription.isVisible=entry!=null
    binding.catalogDescription.text=entry?.description
    binding.installProgress.isVisible=active
    binding.installProgress.isIndeterminate=active && state.phase!=InstallPhase.DOWNLOADING
    if(active && state.phase==InstallPhase.DOWNLOADING) binding.installProgress.setProgressCompat(state.progress,false)
    val size=artifact?.let { Formatter.formatShortFileSize(context,it.size) }.orEmpty()
    binding.installStatus.text=when {
        failure!=null -> context.getString(failure)
        active -> context.getString(when(state.phase) {
            InstallPhase.DOWNLOADING -> R.string.extension_downloading
            InstallPhase.READY -> R.string.extension_ready
            else -> R.string.extension_waiting_installer
        },state.progress)
        action==CatalogAction.INCOMPATIBLE -> context.getString(R.string.extension_not_compatible)
        action==CatalogAction.DIFFERENT_PUBLISHER -> context.getString(R.string.extension_signature_mismatch)
        action==CatalogAction.NEWER -> context.getString(R.string.extension_newer_installed)
        entry!=null -> context.getString(if(entry.channel=="development") R.string.extension_development_version else R.string.extension_catalog_version,entry.versionName,size)
        else -> ""
    }
    binding.installStatus.isVisible=entry!=null
    val cancellable=active && state.phase in setOf(InstallPhase.DOWNLOADING,InstallPhase.READY)
    binding.installExtension.isVisible=cancellable || action in setOf(CatalogAction.INSTALL,CatalogAction.UPDATE)
    binding.installExtension.setChipIconResource(if(cancellable) SettingsR.drawable.source_chip_cancel else SettingsR.drawable.source_chip_download)
    binding.installExtension.setText(if(cancellable) android.R.string.cancel else if(action==CatalogAction.UPDATE) R.string.extension_update else R.string.extension_install)
    binding.installExtension.isEnabled=interactive && (!state.busy || cancellable)
    binding.installExtension.contentDescription=context.getString(if(cancellable) R.string.extension_cancel_name else if(action==CatalogAction.UPDATE) R.string.extension_update_name else R.string.extension_install_name,entry?.name.orEmpty())
    binding.installExtension.setOnClickListener { if(cancellable) cancel() else entry?.let(install) }
}

/** Shared by installed and available cards; rebinding clears a recycled card's previous link. */
private fun bindDownloadLink(view: View,url: String?) {
    view.setOnLongClickListener(url?.let { link -> View.OnLongClickListener {
        val context=it.context
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.extension_download_link),link))
        if(Build.VERSION.SDK_INT<33) Toast.makeText(context,R.string.extension_download_link_copied,Toast.LENGTH_SHORT).show()
        true
    } })
    view.isLongClickable=url!=null
    if(url==null) ViewCompat.removeAccessibilityAction(view,AccessibilityActionCompat.ACTION_LONG_CLICK.id)
    else ViewCompat.replaceAccessibilityAction(view,AccessibilityActionCompat.ACTION_LONG_CLICK,
        view.context.getString(R.string.extension_copy_download_link)) { target,_ -> target.performLongClick() }
}
