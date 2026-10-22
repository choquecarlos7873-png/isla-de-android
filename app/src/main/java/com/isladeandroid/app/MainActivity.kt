package com.isladeandroid.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("island_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var editingPortrait = true
    private var showingSetup = true
    private var statusLabel: TextView? = null
    private var toggleButton: Button? = null
    private var preview: IslandPreviewView? = null
    private val ink = Color.rgb(22, 44, 39)
    private val muted = Color.rgb(83, 107, 98)
    private val teal = Color.rgb(8, 103, 83)
    private val poll = object : Runnable {
        override fun run() {
            if (!showingSetup && !Settings.canDrawOverlays(this@MainActivity)) {
                prefs.edit().putBoolean("enabled", false).apply()
                stopService(Intent(this@MainActivity, IslandOverlayService::class.java))
                renderSetup()
            } else updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingPortrait = savedInstanceState?.getBoolean("editing_portrait") ?: true
    }

    override fun onResume() {
        super.onResume()
        val startAge = android.os.SystemClock.elapsedRealtime() - prefs.getLong("start_requested_at", 0)
        if (prefs.getBoolean("enabled", false) && !IslandOverlayService.isRunning && startAge !in 0L..5000L) {
            prefs.edit().putBoolean("enabled", false).apply()
        }
        if (prefs.getBoolean("setup_complete", false) && Settings.canDrawOverlays(this)) renderControls()
        else renderSetup()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editing_portrait", editingPortrait)
        super.onSaveInstanceState(outState)
    }

    private fun page(kicker: Int, title: Int, subtitle: Int): LinearLayout {
        statusLabel = null
        toggleButton = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(244, 248, 245))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        content.addView(label(getString(kicker), 12, teal, true).apply { letterSpacing = 0.12f })
        content.addView(label(getString(title), 32, ink, true), spaced(10))
        content.addView(label(getString(subtitle), 16, muted), spaced(10))
        return content
    }

    private fun renderSetup() {
        showingSetup = true
        val content = page(R.string.setup_kicker, R.string.setup_title, R.string.setup_intro)
        val overlayAllowed = Settings.canDrawOverlays(this)
        val overlayCard = card()
        content.addView(overlayCard, spaced(24))
        overlayCard.addView(label(getString(R.string.overlay_title), 20, ink, true))
        overlayCard.addView(label(getString(R.string.overlay_reason), 15, muted), spaced(8))
        overlayCard.addView(action(if (overlayAllowed) R.string.permission_granted else R.string.overlay_grant) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, spaced(12))
        val notificationCard = card()
        content.addView(notificationCard, spaced(16))
        notificationCard.addView(label(getString(R.string.notification_title), 20, ink, true))
        notificationCard.addView(label(getString(R.string.notification_reason), 15, muted), spaced(8))
        notificationCard.addView(action(if (notificationsAllowed()) R.string.permission_granted else R.string.notification_grant) {
            if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed() && !prefs.getBoolean("notification_asked", false)) {
                prefs.edit().putBoolean("notification_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
            } else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }, spaced(12))
        content.addView(label(getString(R.string.permissions_scope), 14, muted), spaced(20))
        content.addView(action(R.string.continue_setup) {
            if (Settings.canDrawOverlays(this)) {
                prefs.edit().putBoolean("setup_complete", true).apply()
                renderControls()
            }
        }.apply { isEnabled = overlayAllowed; alpha = if (overlayAllowed) 1f else 0.45f }, spaced(20))
        if (!overlayAllowed) content.addView(label(getString(R.string.overlay_required), 14, muted), spaced(8))
    }

    private fun renderControls() {
        showingSetup = false
        val content = page(R.string.control_kicker, R.string.app_name, R.string.control_intro)
        val hero = card()
        content.addView(hero, spaced(24))
        statusLabel = label("", 16, teal, true).also { hero.addView(it) }
        toggleButton = action(R.string.enable_island) { toggleIsland() }.also { hero.addView(it, spaced(12)) }
        hero.addView(label(getString(R.string.keep_running), 14, muted), spaced(8))
        content.addView(label(getString(R.string.position_title), 24, ink, true), spaced(28))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(action(R.string.portrait) { editingPortrait = true; renderControls() }
            .apply { alpha = if (editingPortrait) 1f else 0.5f }, LinearLayout.LayoutParams(0, dp(52), 1f))
        tabs.addView(action(R.string.landscape) { editingPortrait = false; renderControls() }
            .apply { alpha = if (!editingPortrait) 1f else 0.5f },
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        content.addView(tabs, spaced(12))
        preview = IslandPreviewView(this).apply { profile = readProfile(); portrait = editingPortrait }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(16) })
        content.addView(label(getString(R.string.preview_caption), 12, muted), spaced(4))
        val settings = card()
        content.addView(settings, spaced(16))
        settings.addView(Switch(this).apply {
            text = getString(R.string.automatic_camera)
            textSize = 16f
            setTextColor(ink)
            minHeight = dp(56)
            isChecked = readProfile().autoCamera
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(prefix() + "auto", checked).apply()
                updatePreview()
            }
        })
        settings.addView(label(getString(R.string.automatic_hint), 14, muted), spaced(6))
        slider(settings, R.string.length, "length", 56, 280, 124)
        slider(settings, R.string.thickness, "thickness", 16, 80, 32)
        slider(settings, R.string.offset, "offset", -180, 180, 0)
        slider(settings, R.string.gap, "gap", 0, 120, 8)
        settings.addView(action(R.string.reset_profile) {
            val edit = prefs.edit()
            listOf("length", "thickness", "offset", "gap", "auto").forEach { edit.remove(prefix() + it) }
            edit.apply()
            renderControls()
        }, spaced(16))
        content.addView(label(getString(R.string.rotation_hint), 14, muted), spaced(16))
        content.addView(action(R.string.review_permissions) { renderSetup() }, spaced(24))
        content.addView(action(R.string.battery_settings) {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, spaced(8))
        content.addView(label(getString(R.string.battery_hint), 14, muted), spaced(10))
        content.addView(label(getString(R.string.platform_limits), 14, muted), spaced(16))
        updateStatus()
    }

    private fun toggleIsland() {
        if (IslandOverlayService.isRunning || prefs.getBoolean("enabled", false)) {
            prefs.edit().putBoolean("enabled", false).apply()
            stopService(Intent(this, IslandOverlayService::class.java))
        } else if (Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("enabled", true)
                .putLong("start_requested_at", android.os.SystemClock.elapsedRealtime()).remove("last_error").apply()
            try { startForegroundService(Intent(this, IslandOverlayService::class.java)) }
            catch (_: RuntimeException) {
                prefs.edit().putBoolean("enabled", false).putString("last_error", getString(R.string.start_failed)).apply()
            }
        } else { renderSetup(); return }
        updateStatus()
    }

    private fun updateStatus() {
        val desired = prefs.getBoolean("enabled", false)
        val running = IslandOverlayService.isRunning
        val error = prefs.getString("last_error", null)
        statusLabel?.text = when {
            !error.isNullOrBlank() -> error
            running -> getString(R.string.state_active)
            desired -> getString(R.string.state_starting)
            else -> getString(R.string.state_off)
        }
        toggleButton?.setText(if (running || desired) R.string.disable_island else R.string.enable_island)
    }

    private fun slider(parent: LinearLayout, title: Int, key: String, min: Int, max: Int, default: Int) {
        val value = prefs.getInt(prefix() + key, default).coerceIn(min, max)
        val caption = label(getString(R.string.measurement, getString(title), value), 16, ink, true)
        parent.addView(caption, spaced(20))
        parent.addView(SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            minimumHeight = dp(48)
            contentDescription = getString(title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = progress + min
                    caption.text = getString(R.string.measurement, getString(title), next)
                    prefs.edit().putInt(prefix() + key, next).apply()
                    updatePreview()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun prefix() = if (editingPortrait) "portrait_" else "landscape_"
    private fun readProfile() = IslandProfile(
        prefs.getInt(prefix() + "length", 124), prefs.getInt(prefix() + "thickness", 32),
        prefs.getInt(prefix() + "offset", 0), prefs.getInt(prefix() + "gap", 8), prefs.getBoolean(prefix() + "auto", true))
    private fun updatePreview() { preview?.profile = readProfile() }
    private fun notificationsAllowed() =
        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201) renderSetup()
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(this, R.string.settings_unavailable, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() }
    }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun action(title: Int, click: () -> Unit) = Button(this).apply {
        setText(title); isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
        minHeight = dp(52); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(teal); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { click() }
    }
    private fun spaced(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
