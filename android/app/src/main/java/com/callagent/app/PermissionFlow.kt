package com.callagent.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Asks for everything the app needs, in one guided pass:
 * an overview popup with the reason for each permission, then the standard Android prompts
 * one after another, then the two settings-only switches (battery, accessibility).
 * Create it as an Activity property (it registers activity-result launchers).
 */
class PermissionFlow(
    private val activity: AppCompatActivity,
    private val onChanged: () -> Unit,
) {
    class Item(val title: Int, val reason: Int, val granted: () -> Boolean)

    private val prefs by lazy { Prefs(activity) } // the activity has no context yet at construction
    private var step = 0
    private var running = false
    private var restrictedHelpShown = false

    private val runtimeLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            onChanged()
            val blocked = result.filterValues { !it }.keys
                .filter { !activity.shouldShowRequestPermissionRationale(it) }
            if (blocked.isNotEmpty()) blockedDialog() else next()
        }

    private val settingsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onChanged()
            next()
        }

    private val accessibilityLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onChanged()
            if (!accessibilityOn() && Build.VERSION.SDK_INT >= 33 && !restrictedHelpShown) restrictedDialog()
            else next()
        }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() =
        (activity.getSystemService(AppCompatActivity.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(activity.packageName)

    private fun accessibilityOn() = OwnRecordings.accessibilityEnabled(activity)

    private fun runtimePermissions(): List<String> = buildList {
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)
        add(RecordingFinder.AUDIO_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val items: List<Item> = buildList {
        add(Item(R.string.p_call_log, R.string.p_call_log_why) { has(Manifest.permission.READ_CALL_LOG) })
        add(Item(R.string.p_phone, R.string.p_phone_why) { has(Manifest.permission.READ_PHONE_STATE) })
        add(Item(R.string.p_mic, R.string.p_mic_why) { has(Manifest.permission.RECORD_AUDIO) })
        add(Item(R.string.p_audio, R.string.p_audio_why) { has(RecordingFinder.AUDIO_PERMISSION) })
        if (Build.VERSION.SDK_INT >= 33) {
            add(Item(R.string.p_notify, R.string.p_notify_why) { has(Manifest.permission.POST_NOTIFICATIONS) })
        }
        add(Item(R.string.p_battery, R.string.p_battery_why) { batteryExempt() })
        add(Item(R.string.p_accessibility, R.string.p_accessibility_why) { accessibilityOn() })
    }

    fun missing(): List<Item> = items.filter { !it.granted() }

    /** Overview popup the first time, straight to the prompts afterwards. */
    fun start() {
        if (running || missing().isEmpty()) return
        if (!prefs.permissionIntroShown) introDialog() else begin()
    }

    private fun begin() {
        running = true
        step = 0
        restrictedHelpShown = false
        next()
    }

    private fun next() {
        if (!running) return
        when (step++) {
            0 -> {
                val ask = runtimePermissions().filter { !has(it) }
                if (ask.isEmpty()) next() else runtimeLauncher.launch(ask.toTypedArray())
            }
            1 -> if (batteryExempt()) next() else open(
                settingsLauncher,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
            )
            2 -> if (accessibilityOn()) next() else accessibilityDialog()
            else -> {
                running = false
                onChanged()
            }
        }
    }

    private fun appSettings() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))

    /** Some ROMs lack a settings screen; skip the step instead of crashing. */
    private fun open(launcher: ActivityResultLauncher<Intent>, intent: Intent) {
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            next()
        }
    }

    private fun stop() {
        running = false
        onChanged()
    }

    private fun introDialog() {
        val text = items.joinToString("\n\n") {
            "• " + activity.getString(it.title) + "\n" + activity.getString(it.reason)
        } + "\n\n" + activity.getString(R.string.p_intro_footer)
        AlertDialog.Builder(activity)
            .setTitle(R.string.p_intro_title)
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(R.string.p_continue) { _, _ ->
                prefs.permissionIntroShown = true
                begin()
            }
            .setNegativeButton(R.string.p_later) { _, _ -> prefs.permissionIntroShown = true }
            .show()
    }

    private fun accessibilityDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.p_accessibility)
            .setMessage(R.string.p_accessibility_steps)
            .setCancelable(false)
            .setPositiveButton(R.string.p_open_settings) { _, _ ->
                open(accessibilityLauncher, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.p_later) { _, _ -> stop() }
            .show()
    }

    /** Android 13+: apps installed from a file need "Allow restricted settings" first. */
    private fun restrictedDialog() {
        restrictedHelpShown = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.p_restricted_title)
            .setMessage(R.string.p_restricted_steps)
            .setCancelable(false)
            .setPositiveButton(R.string.p_open_app_settings) { _, _ ->
                step = 2 // back to the accessibility step afterwards
                open(settingsLauncher, appSettings())
            }
            .setNegativeButton(R.string.p_later) { _, _ -> stop() }
            .show()
    }

    /** "Don't ask again" was chosen: only the app's settings page can grant it now. */
    private fun blockedDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.p_blocked_title)
            .setMessage(R.string.p_blocked_steps)
            .setCancelable(false)
            .setPositiveButton(R.string.p_open_app_settings) { _, _ ->
                open(settingsLauncher, appSettings())
            }
            .setNegativeButton(R.string.p_skip) { _, _ -> next() }
            .show()
    }
}
