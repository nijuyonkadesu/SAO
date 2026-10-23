package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import one.njk.sao.R
import one.njk.sao.databinding.ItemTelegramAppBinding
import javax.inject.Inject

data class AppChoice(val packageName: String, val label: String, val icon: Drawable?, val installed: Boolean)

class AppCatalog @Inject constructor(@ApplicationContext private val context: Context, private val dispatchers: FloatingDispatchers) {
    suspend fun load(extras: Set<String>): List<AppChoice> = withContext(dispatchers.io) {
        val manager = context.packageManager
        val launchable = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        val packages = launchable + extras + MessagingTarget.entries.flatMap { it.packages }
        packages.map { pkg ->
            try {
                val info = manager.getApplicationInfo(pkg, 0)
                AppChoice(pkg, manager.getApplicationLabel(info).toString(), manager.getApplicationIcon(info), true)
            } catch (_: PackageManager.NameNotFoundException) { AppChoice(pkg, pkg, null, false) }
        }.filter { it.packageName != context.packageName }.sortedWith(compareBy({ !it.installed }, { it.label.lowercase() }, { it.packageName }))
    }
}

class AppChoiceAdapter(private val items: List<AppChoice>, private val selected: Set<String>? = null,
    private val choose: (AppChoice) -> Unit) : RecyclerView.Adapter<AppChoiceAdapter.Holder>() {
    class Holder(val binding: ItemTelegramAppBinding) : RecyclerView.ViewHolder(binding.root)
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemTelegramAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val builtin = item.packageName in MessagingTarget.TELEGRAM.packages
        holder.binding.apply {
            appIcon.setImageDrawable(item.icon); appLabel.text = item.label
            appPackage.text = item.packageName + when { !item.installed -> " · ${root.context.getString(R.string.floating_not_installed)}"; builtin && selected != null -> " · ${root.context.getString(R.string.floating_builtin_app)}"; else -> "" }
            appCheck.isVisible = selected != null
            appCheck.isChecked = builtin || item.packageName in selected.orEmpty()
            appCheck.isEnabled = !builtin
            appCheck.setOnClickListener { choose(item) }
            root.setOnClickListener { if (!builtin || selected == null) choose(item) }
        }
    }
}
