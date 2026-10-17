package one.njk.sao.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Choreographer
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import one.njk.sao.R
import one.njk.sao.floating.nativebridge.NativeBridgeRegistry
import one.njk.sao.floating.nativebridge.NativeBridgeVisibility
import kotlin.math.abs
import javax.inject.Inject

@AndroidEntryPoint
class FloatingService : Service() {
    @Inject lateinit var preferences: FloatingPreferences
    @Inject lateinit var dispatchers: FloatingDispatchers
    @Inject lateinit var detector: ForegroundAppDetector
    @Inject lateinit var runtime: FloatingRuntime
    private lateinit var scope: CoroutineScope
    private lateinit var windowContext: Context
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var dismissTarget: FloatingDismissTarget
    private var bubble: FloatingActionButton? = null
    private var attached = false
    private var opening = false
    private var wantsVisible = false
    private var hiddenForPicker = false
    private var dragging = false
    private var idleJob: Job? = null
    private var wakeRecovery: Job? = null
    private var idleVersion = 0L
    private var idleTucked = false
    private var tuckAnimation: ValueAnimator? = null
    private lateinit var bounds: android.graphics.Rect
    private lateinit var safeInsets: android.graphics.Insets
    private var imeBottom = 0
    private var displayGeometry: Triple<Int, Int, Int>? = null
    private val displayChanged = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY) return
            val display = displayManager.getDisplay(id) ?: return
            val geometry = Triple(display.rotation, display.mode.physicalWidth, display.mode.physicalHeight)
            if (geometry != displayGeometry) {
                displayGeometry = geometry
                scheduleGeometryUpdate()
            }
        }
    }
    private var layoutPosted = false
    private val dragFrame = Runnable { layoutPosted = false; if (attached) windowManager.updateViewLayout(bubble, params) }
    private var geometryPosted = false
    private val geometryFrame = Choreographer.FrameCallback {
        geometryPosted = false
        cancelDrag()
        positionBubble()
        bubble?.alpha = if (idleTucked) IDLE_ALPHA else 1f
        if (attached) {
            windowManager.updateViewLayout(bubble, params)
            if (!idleTucked) scheduleIdle()
        }
    }
    private val windowConfiguration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            scope.launch { refreshBubbleTheme(); scheduleGeometryUpdate() }
        }
        override fun onLowMemory() = Unit
    }
    private var longPress: Job? = null
    private var options = FloatingOptions()
    private lateinit var params: WindowManager.LayoutParams
    private val overlayPermissionChanged = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName)
            scope.launch { syncBubble() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                wakeRecovery?.cancel(); cancelDrag(); cancelIdle(restore = false); removeBubble(); FloatingSession.icon(FloatingSession.IconState.HIDDEN)
            }
            else {
                // SystemUI may notify before KeyguardManager reflects the final unlocked state.
                wakeRecovery?.cancel()
                wakeRecovery = scope.launch {
                    repeat(30) {
                        val unlocked = withContext(dispatchers.io) {
                            getSystemService(PowerManager::class.java).isInteractive &&
                                !getSystemService(KeyguardManager::class.java).isKeyguardLocked
                        }
                        if (unlocked) { syncBubble(); return@launch }
                        delay(100)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        displayManager = getSystemService(DisplayManager::class.java)
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        displayGeometry = Triple(display.rotation, display.mode.physicalWidth, display.mode.physicalHeight)
        windowContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windowManager = windowContext.getSystemService(WindowManager::class.java)
        val themed = bubbleTheme()
        dismissTarget = FloatingDismissTarget(themed, windowManager)
        bubble = object : FloatingActionButton(themed) {
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                refreshBubbleTheme()
                // ViewRoot also delivers the window's configuration on API 30, where a
                // window context cannot yet register its own configuration callbacks.
                scheduleGeometryUpdate()
            }
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                // Attachment can deliver new geometry after a lock/rotation while detached.
                post { refreshBubbleTheme(); scheduleGeometryUpdate() }
            }
        }.apply {
            customSize = dp(40)
            setEnsureMinTouchTargetSize(true)
            setMaxImageSize(dp(24))
            setImageResource(R.drawable.ic_floating_picker)
            contentDescription = getString(R.string.floating_open_picker)
            setOnClickListener { openPicker() }
            setOnLongClickListener { runtime.stop(); true }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) scheduleGeometryUpdate()
            }
        }
        ViewCompat.replaceAccessibilityAction(requireNotNull(bubble), AccessibilityActionCompat.ACTION_LONG_CLICK,
            getString(R.string.floating_stop)) { _, _ -> bubble?.performLongClick() == true }
        // Material adds touch-target insets to its 40 dp body. An exact 48 dp measure
        // is treated as the body size, displacing the icon and adding those insets twice.
        params = FloatingOverlayWindow.params("SAO floating icon")
        cacheGeometry(); installDragging()
        displayManager.registerDisplayListener(displayChanged, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 31) windowContext.registerComponentCallbacks(windowConfiguration)
        ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) },
            ContextCompat.RECEIVER_EXPORTED)
        getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, overlayPermissionChanged)
        // Picker and overlay share this process. Main.immediate restores without a broadcast hop.
        scope.launch { FloatingSession.pickerVisibility.collect { syncBubble() } }
        scope.launch {
            preferences.options.collect {
                val timingChanged = options.autoHide != it.autoHide || options.idleSeconds != it.idleSeconds
                options = it
                if (!attached) positionBubble()
                if (timingChanged && attached) scheduleIdle()
            }
        }
    }

    private fun bubbleTheme(): Context = DynamicColors.wrapContextIfAvailable(
        ContextThemeWrapper(windowContext, R.style.Theme_SAO))

    /** Configuration/attachment events only; no wallpaper polling or background work. */
    private fun refreshBubbleTheme() {
        val button = bubble ?: return
        val themed = bubbleTheme()
        val background = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorPrimaryContainer, "FloatingService")
        val foreground = MaterialColors.getColor(themed, com.google.android.material.R.attr.colorOnPrimaryContainer, "FloatingService")
        if (button.backgroundTintList?.defaultColor != background) button.backgroundTintList = ColorStateList.valueOf(background)
        if (button.imageTintList?.defaultColor != foreground) button.imageTintList = ColorStateList.valueOf(foreground)
        val ripple = MaterialColors.getColor(themed, androidx.appcompat.R.attr.colorControlHighlight, "FloatingService")
        if (button.rippleColorStateList?.defaultColor != ripple) button.setRippleColor(ColorStateList.valueOf(ripple))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            runtime.stop(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val show = PendingIntent.getService(this, 2, Intent(this, FloatingService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val settings = PendingIntent.getActivity(this, 1, Intent(this, FloatingSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_floating_picker)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_hide_description))
            .setContentIntent(settings).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.floating_show), show)
            .addAction(0, getString(R.string.floating_stop), stop).build()
        ServiceCompat.startForeground(this, 21, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        scope.launch {
            // Reject stale sticky restarts after a dismissal committed in another callback.
            if (!runtime.isRequested()) { stopSelf(); return@launch }
            if (!FloatingSession.active.value) {
                FloatingSession.active(true)
                scope.launch {
                    NativeBridgeVisibility.prepare(applicationContext)
                    NativeBridgeVisibility.notifyActive(applicationContext, true)
                }
            }
            cancelIdle()
            wantsVisible = true
            options = preferences.current()
            idleTucked = intent?.action != ACTION_SHOW && options.autoHide
            positionBubble(); if (attached) windowManager.updateViewLayout(bubble, params)
            syncBubble(); if (attached && !idleTucked) scheduleIdle()
        }
        // Prefer system sticky recovery. Genuine system reconnect/update/boot callbacks also
        // recover when a vendor discarded the started-service record. No timer or watchdog.
        return START_STICKY
    }

    private fun syncBubble() {
        if (!Settings.canDrawOverlays(this)) { runtime.stop(); return }
        if (FloatingSession.pickerVisible) {
            if (attached || opening) hiddenForPicker = true
            opening = false; cancelIdle(restore = false); removeBubble(); FloatingSession.icon(FloatingSession.IconState.PICKER); return
        }
        if (hiddenForPicker) { hiddenForPicker = false; wantsVisible = true; idleTucked = false }
        if (!getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            cancelIdle(restore = false); removeBubble(); return
        }
        if (!wantsVisible) { FloatingSession.icon(FloatingSession.IconState.HIDDEN); return }
        if (!attached) try {
            positionBubble(); bubble?.alpha = if (idleTucked) IDLE_ALPHA else 1f; windowManager.addView(bubble, params); attached = true
            FloatingSession.icon(if (idleTucked) FloatingSession.IconState.TUCKED else FloatingSession.IconState.VISIBLE)
            if (!idleTucked) scheduleIdle()
        } catch (_: SecurityException) { runtime.stop() }
    }
    private fun cacheGeometry() {
        val metrics = windowManager.currentWindowMetrics
        bounds = metrics.bounds
        safeInsets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        imeBottom = metrics.windowInsets.getInsets(WindowInsets.Type.ime()).bottom
    }
    private fun positionBubble() {
        // Re-read the visual context on show/restore too, including rotation while detached.
        cacheGeometry()
        params.x = if (idleTucked) tuckedX() else if (options.edgeEnd) maxOf(safeInsets.left, bounds.width() - safeInsets.right - bubbleWidth() - dp(8)) else safeInsets.left + dp(8)
        val top = safeInsets.top + dp(8); val range = maxOf(0, bounds.height() - safeInsets.bottom - bubbleHeight() - dp(16) - top)
        params.y = top + (range * options.verticalPosition).toInt()
    }
    // Material includes shadow/minimum-touch padding outside customSize. Tuck around its actual center.
    private fun bubbleWidth() = bubble?.width?.takeIf { it > 0 } ?: dp(48)
    private fun bubbleHeight() = bubble?.height?.takeIf { it > 0 } ?: dp(48)
    private fun tuckedX() = if (options.edgeEnd) bounds.width() - safeInsets.right - bubbleWidth()/2 else safeInsets.left - bubbleWidth()/2
    private fun cancelIdle(restore: Boolean = true) {
        idleVersion++; idleJob?.cancel(); idleJob = null
        val moving = tuckAnimation != null
        tuckAnimation?.cancel(); tuckAnimation = null
        if (restore) {
            val wasTucked = idleTucked
            idleTucked = false; bubble?.alpha = 1f
            if ((wasTucked || moving) && attached) { positionBubble(); windowManager.updateViewLayout(bubble, params) }
            if (attached) FloatingSession.icon(FloatingSession.IconState.VISIBLE)
        }
    }
    private fun scheduleIdle() {
        cancelIdle()
        if (!options.autoHide || !attached || opening || dragging || FloatingSession.pickerVisible) return
        val expected = idleVersion
        idleJob = scope.launch {
            delay(options.idleSeconds * 1000L)
            if (expected != idleVersion || !attached || opening || dragging) return@launch
            val startX = params.x; val endX = tuckedX()
            tuckAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                addUpdateListener { animation ->
                    if (expected == idleVersion && attached) {
                        val fraction = animation.animatedValue as Float
                        bubble?.alpha = 1f + (IDLE_ALPHA - 1f) * fraction
                        params.x = (startX + (endX - startX) * fraction).toInt()
                        windowManager.updateViewLayout(bubble, params)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (expected == idleVersion && attached && !opening && !dragging) {
                            idleTucked = true; tuckAnimation = null
                            FloatingSession.icon(FloatingSession.IconState.TUCKED)
                        }
                    }
                })
            }
            tuckAnimation?.start()
        }
    }

    private fun installDragging() {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var dragged = false
        var held = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        fun move(event: MotionEvent) {
            val dx = event.rawX - downX; val dy = event.rawY - downY
            params.x = (originX + dx).toInt().coerceIn(safeInsets.left, maxOf(safeInsets.left, bounds.width() - safeInsets.right - bubbleWidth()))
            params.y = (originY + dy).toInt().coerceIn(safeInsets.top, maxOf(safeInsets.top, bounds.height() - safeInsets.bottom - bubbleHeight()))
            val overTarget = dismissTarget.contains(params.x + bubbleWidth()/2, params.y + bubbleHeight()/2)
            if (dismissTarget.setHovered(overTarget) && overTarget) bubble?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (attached && !layoutPosted) { layoutPosted = true; bubble?.postOnAnimation(dragFrame) }
        }
        bubble?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelIdle(); dragging = true
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; dragged = false; held = false
                    longPress?.cancel()
                    longPress = scope.launch {
                        delay(ViewConfiguration.getLongPressTimeout().toLong())
                        if (attached && !dragged) { held = true; view.performLongClick() }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener true
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) {
                        dragged = true; longPress?.cancel()
                        cacheGeometry(); dismissTarget.show(bounds, safeInsets, imeBottom)
                    }
                    if (dragged) {
                        move(event)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return@setOnTouchListener true
                    longPress?.cancel()
                    if (dragged) move(event)
                    val dismiss = dragged && dismissTarget.hovered
                    cancelDrag()
                    if (dismiss) { runtime.stop(); return@setOnTouchListener true }
                    if (!dragged && !held) view.performClick() else if (dragged) {
                        val top = safeInsets.top + dp(8)
                        val range = maxOf(1, bounds.height() - safeInsets.bottom - bubbleHeight() - dp(16) - top)
                        val end = params.x + bubbleWidth()/2 > bounds.width() / 2
                        options = options.copy(edgeEnd = end, verticalPosition = ((params.y - top).toFloat() / range).coerceIn(0f, 1f))
                        positionBubble(); if (attached) windowManager.updateViewLayout(bubble, params)
                        val edge = options.edgeEnd; val fraction = options.verticalPosition
                        scope.launch { preferences.bubblePosition(edge, fraction) }
                    }
                    scheduleIdle(); true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelDrag(); positionBubble()
                    if (attached) windowManager.updateViewLayout(bubble, params)
                    scheduleIdle(); true
                }
                else -> false
            }
        }
    }

    private fun openPicker() {
        if (opening || FloatingSession.pickerVisible) return
        opening = true; cancelIdle()
        // One-shot reads only on an explicit icon press, in parallel and off Main.
        val foreground = scope.async(dispatchers.io) {
            try { detector.capturePackage() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { null }
        }
        val capture = scope.async { if (options.pasteAssistance) PasteAssistService.prepareTarget() else null }
        scope.launch {
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 250
                val detected = withTimeoutOrNull(150) { foreground.await() }
                val original = withTimeoutOrNull(maxOf(1, deadline - android.os.SystemClock.elapsedRealtime())) { capture.await() }
                if (!FloatingSession.active.value) return@launch
                val nativeSelection = NativeBridgeRegistry.beginSelection(original?.packageName ?: detected)
                val target = original?.packageName ?: detected ?: nativeSelection?.packageName
                startActivity(PickerActivity.intent(this@FloatingService, target, original?.token, nativeSelection?.session)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(1000)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) { /* Keep the icon available if the activity cannot open. */ }
            finally {
                foreground.cancel(); capture.cancel()
                if (opening) { opening = false; scheduleIdle() }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A service's global configuration can precede its visual window context. API 31+
        // uses that context's callback instead; API 30 defers its fallback to a frame.
        if (Build.VERSION.SDK_INT < 31) scheduleGeometryUpdate()
    }

    private fun scheduleGeometryUpdate() {
        if (!scope.isActive) return
        cancelIdle(restore = false)
        cancelDrag()
        if (!geometryPosted) {
            geometryPosted = true
            Choreographer.getInstance().postFrameCallback(geometryFrame)
        }
    }

    private fun cancelDrag() {
        dragging = false; longPress?.cancel(); longPress = null; bubble?.isPressed = false
        bubble?.removeCallbacks(dragFrame); layoutPosted = false
        if (::dismissTarget.isInitialized) dismissTarget.hide()
    }

    private fun removeBubble() {
        cancelDrag()
        if (attached) { windowManager.removeView(bubble); attached = false }
    }
    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayChanged)
        getSystemService(AppOpsManager::class.java).stopWatchingMode(overlayPermissionChanged)
        if (Build.VERSION.SDK_INT >= 31) windowContext.unregisterComponentCallbacks(windowConfiguration)
        Choreographer.getInstance().removeFrameCallback(geometryFrame); geometryPosted = false
        scope.cancel(); cancelIdle(restore = false); cancelDrag(); removeBubble()
        unregisterReceiver(screenReceiver); FloatingSession.active(false)
        NativeBridgeVisibility.notifyActive(applicationContext, false)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun dp(value: Int) = (value * windowContext.resources.displayMetrics.density).toInt()

    companion object {
        private const val IDLE_ALPHA = .35f
        private const val CHANNEL = "floating_picker"
        const val ACTION_SHOW = "one.njk.sao.floating.SHOW"
        const val ACTION_RESTORE = "one.njk.sao.floating.RESTORE"
        const val ACTION_STOP = "one.njk.sao.floating.STOP"
    }
}
