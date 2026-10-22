package com.isladeandroid.app

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import kotlin.math.min

/** A small, opaque visual overlay. It does not inspect apps, notifications or input. */
class IslandOverlayService : Service(), DisplayManager.DisplayListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private var overlayView: View? = null
    private var windowParameters: WindowManager.LayoutParams? = null
    private var lastPlacement: IslandPlacement? = null
    private var foregroundReady = false
    private var displayListenerRegistered = false
    private var preferencesListenerRegistered = false
    private var appOpsManager: AppOpsManager? = null
    private var destroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { updateOverlay() }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // last_error is deliberately excluded: writing an error must not restart the overlay.
        if (key == null || key == "enabled" || key in PROFILE_KEYS) {
            mainHandler.post {
                if (!destroyed) {
                    if (preferences.getBoolean("enabled", false)) scheduleRefresh() else stopSelf()
                }
            }
        }
    }

    private val permissionListener = AppOpsManager.OnOpChangedListener { operation, changedPackage ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && changedPackage == packageName) {
            mainHandler.post {
                if (!destroyed && !Settings.canDrawOverlays(this)) {
                    failAndStop(R.string.service_permission_error)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences("island_settings", MODE_PRIVATE)

        // Meet the foreground-service deadline before creating or measuring any windows.
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.service_channel_description)
                    setShowBadge(false)
                }
            )
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundReady = true
        } catch (exception: RuntimeException) {
            failAndStop(R.string.service_start_error, exception)
            return
        }

        try {
            displayManager = getSystemService(DisplayManager::class.java)
            // A window context supplies display-wide metrics and cutout data on Android 11+.
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val overlayContext: Context = if (Build.VERSION.SDK_INT >= 30 && display != null) {
                createDisplayContext(display).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
                )
            } else {
                this
            }
            windowManager = overlayContext.getSystemService(WindowManager::class.java)
            displayManager.registerDisplayListener(this, mainHandler)
            displayListenerRegistered = true
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            preferencesListenerRegistered = true
            appOpsManager = getSystemService(AppOpsManager::class.java)
            appOpsManager?.startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener
            )
        } catch (exception: RuntimeException) {
            failAndStop(R.string.service_start_error, exception)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            preferences.edit().putBoolean("enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundReady || !::windowManager.isInitialized || !preferences.getBoolean("enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            failAndStop(R.string.service_permission_error)
            return START_NOT_STICKY
        }
        updateOverlay()
        return if (preferences.getBoolean("enabled", false)) START_STICKY else START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleRefresh()
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            overlayView?.requestApplyInsets()
            scheduleRefresh()
        }
    }

    private fun scheduleRefresh() {
        if (!destroyed) {
            // Several configuration/insets events can describe one rotation.
            mainHandler.removeCallbacks(refresh)
            mainHandler.post(refresh)
        }
    }

    @android.annotation.SuppressLint("RtlHardcoded") // Absolute screen coordinates, not text direction.
    private fun updateOverlay() {
        if (destroyed || !::windowManager.isInitialized) return
        if (!preferences.getBoolean("enabled", false)) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            failAndStop(R.string.service_permission_error)
            return
        }

        try {
            val screen = readScreen()
            if (screen.width <= 0 || screen.height <= 0) return
            val prefix = if (screen.width > screen.height) "landscape_" else "portrait_"
            val profile = IslandProfile(
                lengthDp = preferences.getInt(prefix + "length", 124),
                thicknessDp = preferences.getInt(prefix + "thickness", 32),
                offsetDp = preferences.getInt(prefix + "offset", 0),
                gapDp = preferences.getInt(prefix + "gap", 8),
                autoCamera = preferences.getBoolean(prefix + "auto", true)
            )
            val placement = IslandGeometry.place(
                screen.width, screen.height, screen.density, screen.rotation, profile, screen.cutouts
            )
            if (overlayView != null && placement == lastPlacement) return

            val view = overlayView ?: View(this).apply {
                alpha = 1f
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setOnApplyWindowInsetsListener { _, insets ->
                    // Android 9/10 expose cutouts through the attached window's root insets.
                    // updateOverlay compares placements before updating, avoiding insets loops.
                    if (Build.VERSION.SDK_INT in 28..29) scheduleRefresh()
                    insets
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    if (Build.VERSION.SDK_INT in 28..29) scheduleRefresh()
                }
            }
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.BLACK)
                cornerRadius = min(placement.width, placement.height) / 2f
            }
            val parameters = windowParameters ?: WindowManager.LayoutParams(
                placement.width,
                placement.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    // A non-touchable overlay is opacity-clamped by some Android builds.
                    // Keep this tiny window opaque; it has no actions and consumes only its area.
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                // LEFT intentionally uses physical display coordinates, including in RTL locales.
                gravity = Gravity.TOP or Gravity.LEFT
                alpha = 1f
                windowAnimations = 0
                setTitle(getString(R.string.app_name))
                if (Build.VERSION.SDK_INT >= 30) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    setFitInsetsTypes(0)
                } else if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            parameters.width = placement.width
            parameters.height = placement.height
            parameters.x = placement.x
            parameters.y = placement.y
            if (overlayView == null) {
                windowManager.addView(view, parameters)
                overlayView = view
                windowParameters = parameters
                isRunning = true
                preferences.edit().remove("last_error").apply()
                view.requestApplyInsets()
            } else {
                windowManager.updateViewLayout(view, parameters)
            }
            lastPlacement = placement
        } catch (exception: RuntimeException) {
            // Includes SecurityException, BadTokenException, and a removed/revoked overlay window.
            failAndStop(R.string.service_overlay_error, exception)
        }
    }

    @Suppress("DEPRECATION")
    private fun readScreen(): Screen {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val density = resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val cutouts = metrics.windowInsets.displayCutout?.boundingRects.orEmpty().map {
                CameraBounds(it.left, it.top, it.right, it.bottom)
            }
            return Screen(bounds.width(), bounds.height(), density, rotation, cutouts)
        }

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val view = overlayView
        val cutouts = if (Build.VERSION.SDK_INT >= 28 && view != null && view.isLaidOut) {
            // Root-window cutouts use window-relative coordinates. Convert back to the display.
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty().map {
                CameraBounds(
                    it.left + location[0], it.top + location[1],
                    it.right + location[0], it.bottom + location[1]
                )
            }.filter {
                it.left >= 0 && it.top >= 0 && it.right <= metrics.widthPixels &&
                    it.bottom <= metrics.heightPixels && it.right > it.left && it.bottom > it.top
            }
        } else {
            emptyList()
        }
        return Screen(metrics.widthPixels, metrics.heightPixels, metrics.density, rotation, cutouts)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, IslandOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, getString(R.string.service_stop), stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    private fun failAndStop(messageResource: Int, exception: RuntimeException? = null) {
        if (exception != null) Log.w(TAG, getString(messageResource), exception)
        preferences.edit()
            .putString("last_error", getString(messageResource))
            .putBoolean("enabled", false)
            .apply()
        isRunning = false
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        val view = overlayView
        overlayView = null
        windowParameters = null
        lastPlacement = null
        if (view != null && ::windowManager.isInitialized) {
            try {
                windowManager.removeViewImmediate(view)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Overlay window already removed", exception)
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(refresh)
        if (preferencesListenerRegistered) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (displayListenerRegistered) displayManager.unregisterDisplayListener(this)
        try {
            appOpsManager?.stopWatchingMode(permissionListener)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Permission listener already removed", exception)
        }
        removeOverlay()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Keep enabled intact for a system-created sticky service restart.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class Screen(
        val width: Int,
        val height: Int,
        val density: Float,
        val rotation: Int,
        val cutouts: List<CameraBounds>
    )

    companion object {
        const val ACTION_STOP = "com.isladeandroid.app.STOP"
        private const val CHANNEL_ID = "island_active"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "IslandOverlayService"
        private val PROFILE_KEYS = setOf(
            "portrait_length", "portrait_thickness", "portrait_offset", "portrait_gap", "portrait_auto",
            "landscape_length", "landscape_thickness", "landscape_offset", "landscape_gap", "landscape_auto"
        )

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
