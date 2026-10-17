package one.njk.sao.floating

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import one.njk.sao.R
import one.njk.sao.databinding.FragmentFloatingSettingsBinding
import one.njk.sao.floating.nativebridge.NativeBridgeRegistry
import javax.inject.Inject

/** Shared inline controls; collectors and bindings follow the view lifecycle. */
@AndroidEntryPoint
class FloatingSettingsFragment : Fragment() {
    @Inject lateinit var preferences: FloatingPreferences
    @Inject lateinit var dispatchers: FloatingDispatchers
    @Inject lateinit var detector: ForegroundAppDetector
    @Inject lateinit var runtime: FloatingRuntime
    private var _binding: FragmentFloatingSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var pendingStart = false
    private var updating = false
    private var draggingHoldDelay = false
    private var batteryStatusJob: Job? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startFloating() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStart = savedInstanceState?.getBoolean("pending_start") ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFloatingSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.apply {
            startStop.setOnClickListener {
                if (!Settings.canDrawOverlays(requireContext())) {
                    pendingStart = true; openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
                } else startWithNotificationPermission()
            }
            stopFloating.setOnClickListener { runtime.stop() }
            telegramApps.setOnClickListener { startActivity(Intent(requireContext(), TelegramAppsActivity::class.java)) }
            immichSettings.setOnClickListener { startActivity(Intent(requireContext(), SourceSettingsActivity::class.java)) }
            autoHide.setOnCheckedChangeListener { _, checked -> if (!updating) viewLifecycleOwner.lifecycleScope.launch { preferences.autoHide(checked) } }
            showOnLaunch.setOnCheckedChangeListener { _, checked -> if (!updating) viewLifecycleOwner.lifecycleScope.launch { preferences.showOnLaunch(checked) } }
            browseSequence.setOnCheckedChangeListener { _, checked -> if (!updating) viewLifecycleOwner.lifecycleScope.launch { preferences.browseSequence(checked) } }
            longPressDelay.setLabelFormatter { getString(R.string.sequence_hold_value, it.toInt()) }
            longPressDelay.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) { draggingHoldDelay = true }
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                    draggingHoldDelay = false
                    viewLifecycleOwner.lifecycleScope.launch { preferences.longPressMs(slider.value.toInt()) }
                }
            })
            longPressDelay.addOnChangeListener { _, value, fromUser ->
                longPressValue.text = getString(R.string.sequence_hold_value, value.toInt())
                if (fromUser && !draggingHoldDelay && !updating) viewLifecycleOwner.lifecycleScope.launch { preferences.longPressMs(value.toInt()) }
            }
            hideDelay.setOnCheckedStateChangeListener { _, ids -> if (!updating) viewLifecycleOwner.lifecycleScope.launch {
                preferences.idleSeconds(when (ids.firstOrNull()) { R.id.delay_five -> 5; R.id.delay_ten -> 10; else -> 3 })
            } }
            addSamplePack.setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    val context = requireContext()
                    val pkg = withContext(dispatchers.io) {
                        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
                            try { context.packageManager.getPackageInfo(it, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
                        }
                    }
                    try {
                        if (pkg == null) throw android.content.ActivityNotFoundException()
                        startActivity(Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").setPackage(pkg)
                            .putExtra("sticker_pack_id", SampleStickerProvider.PACK)
                            .putExtra("sticker_pack_authority", "${context.packageName}.stickers")
                            .putExtra("sticker_pack_name", getString(R.string.floating_pack_title)))
                    } catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, R.string.floating_app_unavailable, Toast.LENGTH_SHORT).show() }
                }
            }
            preview.setOnClickListener { startActivity(PickerActivity.intent(requireContext(), null, null)) }
            overlayPermission.setOnClickListener { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
            usagePermission.setOnClickListener { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            accessibilityPermission.setOnClickListener { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            backgroundReliability.batteryOptimization.setOnClickListener { openBatterySettings() }
            backgroundReliability.backgroundSettings.setOnClickListener { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
            preferStickers.setOnCheckedChangeListener { _, checked ->
                if (!updating) viewLifecycleOwner.lifecycleScope.launch { preferences.preferStickers(checked) }
            }
            pasteAssistance.setOnCheckedChangeListener { _, checked ->
                if (!updating) viewLifecycleOwner.lifecycleScope.launch { preferences.pasteAssistance(checked) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { preferences.options.collect { options ->
                    updating = true
                    renderToggle(binding.preferStickers, options.preferStickers, R.string.floating_prefer_stickers)
                    renderToggle(binding.pasteAssistance, options.pasteAssistance, R.string.floating_paste_assistance)
                    renderToggle(binding.autoHide, options.autoHide, R.string.floating_auto_hide)
                    renderToggle(binding.showOnLaunch, options.showOnLaunch, R.string.floating_show_on_launch)
                    renderToggle(binding.browseSequence, options.browseSequence, R.string.sequence_setting)
                    binding.telegramApps.isActivated = options.telegramPackages.isNotEmpty()
                    if (!draggingHoldDelay) binding.longPressValue.text = getString(R.string.sequence_hold_value, options.longPressMs)
                    if (!draggingHoldDelay) binding.longPressDelay.value = options.longPressMs.toFloat()
                    binding.hideDelay.check(when (options.idleSeconds) { 5 -> R.id.delay_five; 10 -> R.id.delay_ten; else -> R.id.delay_three })
                    for (i in 0 until binding.hideDelay.childCount) binding.hideDelay.getChildAt(i).isEnabled = options.autoHide
                    updating = false
                } }
                launch { FloatingSession.iconState.collect { state ->
                    binding.sessionStatus.setText(when (state) {
                        FloatingSession.IconState.STOPPED -> R.string.floating_stopped
                        FloatingSession.IconState.HIDDEN -> R.string.floating_icon_unavailable
                        FloatingSession.IconState.TUCKED -> R.string.floating_idle_hidden
                        FloatingSession.IconState.PICKER -> R.string.floating_picker_open
                        FloatingSession.IconState.VISIBLE -> R.string.floating_running
                    })
                    binding.stopFloating.isEnabled = state != FloatingSession.IconState.STOPPED
                    binding.startStop.isActivated = state != FloatingSession.IconState.STOPPED
                } }
                launch { NativeBridgeRegistry.connected.collect { connected ->
                    binding.nativeStatus.setText(if (connected) R.string.floating_native_ready else R.string.floating_native_unavailable)
                } }
            }
        }
    }

    private fun renderToggle(chip: Chip, enabled: Boolean, title: Int) {
        chip.isChecked = enabled
        val label = getString(if (enabled) R.string.enabled else R.string.disabled)
        if (chip.text.toString() != label) chip.text = label
        chip.contentDescription = "${getString(title)}: $label"
    }

    override fun onDestroyView() {
        batteryStatusJob?.cancel(); batteryStatusJob = null
        draggingHoldDelay = false
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        renderPermission(binding.overlayPermission, Settings.canDrawOverlays(requireContext()), R.string.floating_overlay_permission)
        renderPermission(binding.usagePermission, detector.hasAccess(), R.string.floating_usage_permission)
        renderPermission(binding.accessibilityPermission, PasteAssistService.connected(), R.string.floating_settings_accessibility)
        refreshBatteryStatus()
        if (pendingStart) {
            pendingStart = false
            if (Settings.canDrawOverlays(requireContext())) startWithNotificationPermission()
        }
    }

    private fun refreshBatteryStatus() {
        val context = requireContext().applicationContext
        batteryStatusJob?.cancel()
        batteryStatusJob = viewLifecycleOwner.lifecycleScope.launch {
            val (exempt, lastExit) = withContext(dispatchers.io) {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) to
                    FloatingExitInfo.read(context)
            }
            val chip = _binding?.backgroundReliability?.batteryOptimization ?: return@launch
            val label = getString(if (exempt) R.string.floating_battery_exempt else R.string.floating_battery_optimized)
            chip.text = label
            chip.isActivated = exempt
            chip.contentDescription = "${getString(R.string.floating_battery_optimization)}: $label"
            _binding?.backgroundReliability?.lastAppExit?.apply {
                text = lastExit
                visibility = if (lastExit == null) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onPause() {
        batteryStatusJob?.cancel(); batteryStatusJob = null
        super.onPause()
    }

    private fun openBatterySettings() {
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        }
    }

    private fun renderPermission(chip: Chip, granted: Boolean, title: Int) {
        chip.isActivated = granted
        val label = getString(if (granted) R.string.floating_permission_granted else R.string.floating_permission_not_granted)
        if (chip.text.toString() != label) chip.text = label
        chip.contentDescription = "${getString(title)}: $label"
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_start", pendingStart); super.onSaveInstanceState(outState)
    }
    private fun startWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startFloating()
    }
    private fun startFloating() {
        if (Settings.canDrawOverlays(requireContext())) runtime.show()
    }
    private fun openSettings(action: String, packageData: Boolean = false) {
        try { startActivity(Intent(action).apply { if (packageData) data = "package:${requireContext().packageName}".toUri() }) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(requireContext(), R.string.floating_app_unavailable, Toast.LENGTH_SHORT).show() }
    }
}
