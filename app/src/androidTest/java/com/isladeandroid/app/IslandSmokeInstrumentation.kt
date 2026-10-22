package com.isladeandroid.app

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in, device smoke test. Run only after the owner authorizes device testing.
 * Does not grant/revoke permissions, inspect other apps or capture their screens.
 * Restores profile/rotation settings and deliberately leaves the island disabled.
 */
class IslandSmokeInstrumentation : Instrumentation() {
    private val resumed = AtomicReference<MainActivity?>()
    private val report = mutableListOf<String>()
    private var failures = 0

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        if (activity is MainActivity) resumed.set(activity)
    }

    override fun onStart() {
        super.onStart()
        val prefs = targetContext.getSharedPreferences("island_settings", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        var originalAccelerometer: String? = null
        var originalUserRotation: String? = null
        var rotationTouched = false
        try {
            check(Settings.canDrawOverlays(targetContext)) { "Owner must grant overlay permission first" }
            originalAccelerometer = readSetting("accelerometer_rotation")
            originalUserRotation = readSetting("user_rotation")
            main {
                prefs.edit().putBoolean("enabled", false).putBoolean("setup_complete", true).commit()
                targetContext.stopService(serviceIntent())
            }
            launchActivity()
            await("MainActivity focus") { hasAppFocus() }
            await("initial stopped state") { !IslandOverlayService.isRunning }

            step("Activate: service, notification and visible UI state") {
                configureProfile(prefs, 124, 32, 0, 60)
                enable(prefs)
                await("overlay starts") { IslandOverlayService.isRunning }
                await("active UI state") { appContainsText(targetContext.getString(R.string.state_active)) }
                val notifications = targetContext.getSystemService(NotificationManager::class.java).activeNotifications
                check(notifications.any { it.id == 1001 }) { "Foreground-service notification missing" }
            }

            for ((name, rotation) in listOf(
                "portrait" to Surface.ROTATION_0,
                "landscape-left" to Surface.ROTATION_90,
                "portrait-reverse" to Surface.ROTATION_180,
                "landscape-right" to Surface.ROTATION_270,
            )) {
                step("Opaque black and bounds: $name") {
                    rotationTouched = true
                    check(uiAutomation.setRotation(rotation)) { "Device refused rotation $rotation" }
                    await("display rotation $rotation") { displayRotation() == rotation && hasAppFocus() }
                    configureProfile(prefs, 124, 32, 0, 60)
                    SystemClock.sleep(900)
                    verifyBlack(prefs, "smoke-black-$name.png")
                }
            }

            step("Live changes: size, thickness, offset and gap") {
                configureProfile(prefs, 156, 44, 75, 90)
                SystemClock.sleep(900)
                verifyBlack(prefs, "smoke-black-live-settings.png")
            }

            step("Disable, reactivate and UI status") {
                main {
                    prefs.edit().putBoolean("enabled", false).commit()
                    targetContext.stopService(serviceIntent())
                }
                await("overlay stops") { !IslandOverlayService.isRunning }
                await("disabled UI state") { appContainsText(targetContext.getString(R.string.state_off)) }
                enable(prefs)
                await("overlay restarts") { IslandOverlayService.isRunning }
            }

            step("Foreground service persists outside MainActivity") {
                main { checkNotNull(resumed.get()).moveTaskToBack(true) }
                SystemClock.sleep(1500)
                check(IslandOverlayService.isRunning) { "Service stopped when Activity went to background" }
                // No screenshot while another app/launcher may be visible.
                launchActivity()
                await("MainActivity returns") { hasAppFocus() }
            }

            step("Service survives system notification panel") {
                try {
                    shell("cmd statusbar expand-notifications")
                    SystemClock.sleep(850)
                    check(IslandOverlayService.isRunning) { "Service stopped while notification panel was open" }
                } finally {
                    shell("cmd statusbar collapse")
                }
                await("MainActivity focus after panel closes") { hasAppFocus() }
                check(IslandOverlayService.isRunning) { "Service stopped after notification panel closed" }
                // System UI may cover the island by platform design. Never capture this panel.
            }
        } catch (error: Throwable) {
            failures++
            report += "FAIL precondition or setup: ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            try {
                shell("cmd statusbar collapse")
                if (rotationTouched) uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
                originalUserRotation?.let { restoreSetting("user_rotation", it) }
                originalAccelerometer?.let { restoreSetting("accelerometer_rotation", it) }
                main {
                    val editor = prefs.edit().clear()
                    saved.forEach { (key, value) -> restoreValue(editor, key, value) }
                    editor.putBoolean("enabled", false).commit()
                    targetContext.stopService(serviceIntent())
                }
                await("final stopped state") { !IslandOverlayService.isRunning }
                launchActivity()
                await("final app focus") { hasAppFocus() }
                report += "RESTORED profiles and rotation settings; permissions unchanged; island disabled"
            } catch (restoreError: Throwable) {
                failures++
                report += "FAIL restoring state: ${restoreError.javaClass.simpleName}: ${restoreError.message}"
            }
            val text = report.joinToString("\n")
            File(targetContext.filesDir, "smoke-report.txt").writeText(text)
            finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "\n$text\n")
                putInt("failures", failures)
                putString("result", if (failures == 0) "PASS" else "FAIL")
            })
        }
    }

    private fun step(name: String, test: () -> Unit) {
        try {
            test()
            report += "PASS $name"
        } catch (error: Throwable) {
            failures++
            report += "FAIL $name: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun launchActivity() {
        startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        waitForIdleSync()
    }

    private fun serviceIntent() = Intent(targetContext, IslandOverlayService::class.java)

    private fun enable(prefs: SharedPreferences) {
        main {
            prefs.edit().putBoolean("enabled", true)
                .putLong("start_requested_at", SystemClock.elapsedRealtime()).remove("last_error").commit()
            targetContext.startForegroundService(serviceIntent())
        }
    }

    private fun configureProfile(prefs: SharedPreferences, length: Int, thickness: Int, offset: Int, gap: Int) {
        main {
            val edit = prefs.edit()
            for (prefix in listOf("portrait_", "landscape_")) {
                edit.putInt(prefix + "length", length)
                    .putInt(prefix + "thickness", thickness)
                    .putInt(prefix + "offset", offset)
                    .putInt(prefix + "gap", gap)
                    .putBoolean(prefix + "auto", false)
            }
            check(edit.commit()) { "Profile persistence failed" }
        }
    }

    private fun verifyBlack(prefs: SharedPreferences, filename: String) {
        check(IslandOverlayService.isRunning) { "Overlay not running" }
        await("foreground Activity before screenshot") { hasAppFocus() }
        val screenshot = checkNotNull(uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        // A focus change could mean system UI or another app appeared during capture.
        if (!hasAppFocus()) {
            screenshot.recycle()
            error("Discarded screenshot because our Activity lost focus")
        }
        val bitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false)
        screenshot.recycle()
        try {
            val prefix = if (bitmap.width > bitmap.height) "landscape_" else "portrait_"
            val profile = IslandProfile(
                prefs.getInt(prefix + "length", 124), prefs.getInt(prefix + "thickness", 32),
                prefs.getInt(prefix + "offset", 0), prefs.getInt(prefix + "gap", 60), false,
            )
            val placement = IslandGeometry.place(
                bitmap.width, bitmap.height, targetContext.resources.displayMetrics.density,
                displayRotation(), profile,
            )
            check(placement.x >= 0 && placement.y >= 0 &&
                placement.x + placement.width <= bitmap.width &&
                placement.y + placement.height <= bitmap.height) { "Overlay geometry outside display" }
            val horizontal = placement.width >= placement.height
            val samples = listOf(0.25f, 0.5f, 0.75f).map { fraction ->
                val x = placement.x + (placement.width * if (horizontal) fraction else 0.5f).toInt()
                val y = placement.y + (placement.height * if (horizontal) 0.5f else fraction).toInt()
                Triple(x, y, bitmap.getPixel(x, y))
            }
            // Keep failed visual evidence too, provided it contains only our foreground Activity.
            File(targetContext.filesDir, filename).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val colors = samples.joinToString { (_, _, color) -> "#%08X".format(color) }
            report += "PIXELS $filename: $colors; screen=${bitmap.width}x${bitmap.height}; placement=$placement"
            check(samples.all { it.third == Color.BLACK }) { "Island contains non-black pixels: $colors" }
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = targetContext.getSystemService(android.hardware.display.DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    private fun hasAppFocus(): Boolean {
        var result = false
        main {
            result = resumed.get()?.let { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } == true
        }
        return result
    }

    private fun appContainsText(text: String): Boolean {
        var found = false
        main {
            fun visit(view: View) {
                if (view is TextView && view.text.toString() == text) found = true
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            resumed.get()?.window?.decorView?.let(::visit)
        }
        return found
    }

    private fun main(action: () -> Unit) = runOnMainSync { action() }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $description" }
            SystemClock.sleep(100)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private fun readSetting(key: String): String = shell("settings get system $key").also {
        check(it == "null" || it.matches(Regex("-?\\d+"))) { "Could not preserve rotation setting $key" }
    }

    private fun restoreSetting(key: String, value: String) {
        if (value == "null") shell("settings delete system $key")
        else shell("settings put system $key $value")
        check(readSetting(key) == value) { "Failed to restore $key" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value as Set<String>)
        }
    }
}
