package com.callagent.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/** Status screen: permissions, phone compatibility check, upload queue. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: CallStore

    private lateinit var title: TextView
    private lateinit var server: TextView
    private lateinit var revokedBox: View
    private lateinit var permCallLog: Button
    private lateinit var permAudio: Button
    private lateinit var permBattery: Button
    private lateinit var permMic: Button
    private lateinit var permPhone: Button
    private lateinit var permNotify: Button
    private lateinit var accessibility: Button
    private lateinit var restrictedHint: View
    private lateinit var openAppSettings: Button
    private lateinit var audioSource: Button
    private lateinit var checkCompat: Button
    private lateinit var compatResult: TextView
    private lateinit var folder: TextView
    private lateinit var queue: TextView
    private lateinit var lastSync: TextView
    private lateinit var lastError: TextView

    private var pendingPermission: String? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val perm = pendingPermission
            // "don't ask again": the system won't show the dialog, send the user to settings
            if (!granted && perm != null && !shouldShowRequestPermissionRationale(perm)) {
                Toast.makeText(this, R.string.open_settings, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            if (granted) SyncWorker.syncNow(this)
            refresh()
        }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.recordingsTreeUri = uri.toString()
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.isActivated && !prefs.revoked) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        store = CallStore(this)

        title = findViewById(R.id.title)
        server = findViewById(R.id.server)
        revokedBox = findViewById(R.id.revoked_box)
        permCallLog = findViewById(R.id.perm_call_log)
        permAudio = findViewById(R.id.perm_audio)
        permBattery = findViewById(R.id.perm_battery)
        permMic = findViewById(R.id.perm_mic)
        permPhone = findViewById(R.id.perm_phone)
        permNotify = findViewById(R.id.perm_notify)
        accessibility = findViewById(R.id.accessibility)
        restrictedHint = findViewById(R.id.restricted_hint)
        openAppSettings = findViewById(R.id.open_app_settings)
        audioSource = findViewById(R.id.audio_source)
        checkCompat = findViewById(R.id.check_compat)
        compatResult = findViewById(R.id.compat_result)
        folder = findViewById(R.id.folder)
        queue = findViewById(R.id.queue)
        lastSync = findViewById(R.id.last_sync)
        lastError = findViewById(R.id.last_error)

        permCallLog.setOnClickListener { ask(Manifest.permission.READ_CALL_LOG) }
        permAudio.setOnClickListener { ask(RecordingFinder.AUDIO_PERMISSION) }
        permBattery.setOnClickListener { askBatteryExemption() }
        permMic.setOnClickListener { ask(Manifest.permission.RECORD_AUDIO) }
        permPhone.setOnClickListener { ask(Manifest.permission.READ_PHONE_STATE) }
        permNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) ask(Manifest.permission.POST_NOTIFICATIONS)
        }
        accessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        openAppSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        audioSource.setOnClickListener { chooseAudioSource() }
        checkCompat.setOnClickListener { runCompatibilityCheck() }
        findViewById<Button>(R.id.pick_folder).setOnClickListener { folderPicker.launch(null) }
        findViewById<Button>(R.id.sync_now).setOnClickListener {
            SyncWorker.syncNow(this)
            Toast.makeText(this, R.string.sync_started, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.reactivate).setOnClickListener {
            prefs.clearActivation()
            SyncWorker.cancel(this)
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }

        // keep the status fresh while the screen is visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(3000)
                }
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ask(permission: String) {
        pendingPermission = permission
        permissionLauncher.launch(permission)
    }

    private fun batteryExempt() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showPermission(button: Button, label: Int, ok: Boolean) {
        button.text = if (ok) "${getString(R.string.ok_mark)} — ${getString(label)}"
        else "${getString(R.string.grant)}: ${getString(label)}"
        button.isEnabled = !ok
    }

    private fun refresh() {
        title.text = getString(R.string.activated_as, prefs.deviceName.orEmpty())
        server.text = getString(R.string.server_label, prefs.serverUrl.orEmpty())
        revokedBox.visibility = if (prefs.revoked) View.VISIBLE else View.GONE

        showPermission(permCallLog, R.string.perm_call_log, granted(Manifest.permission.READ_CALL_LOG))
        showPermission(permAudio, R.string.perm_audio, granted(RecordingFinder.AUDIO_PERMISSION))
        showPermission(permBattery, R.string.perm_battery, batteryExempt())
        showPermission(permMic, R.string.perm_mic, granted(Manifest.permission.RECORD_AUDIO))
        showPermission(permPhone, R.string.perm_phone, granted(Manifest.permission.READ_PHONE_STATE))
        if (Build.VERSION.SDK_INT >= 33) {
            showPermission(permNotify, R.string.perm_notify, granted(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            permNotify.visibility = View.GONE
        }
        val serviceOn = OwnRecordings.accessibilityEnabled(this)
        showPermission(accessibility, R.string.accessibility_on, serviceOn)
        accessibility.isEnabled = true // also lets the user turn it off again
        restrictedHint.visibility = if (serviceOn) View.GONE else View.VISIBLE
        openAppSettings.visibility = restrictedHint.visibility
        val names = resources.getStringArray(R.array.audio_source_names)
        val idx = RecordingService.SOURCES.indexOf(prefs.audioSource).coerceAtLeast(0)
        audioSource.text = getString(R.string.audio_source, names[idx])

        folder.text = prefs.recordingsTreeUri?.let {
            getString(R.string.folder_label, Uri.parse(it).lastPathSegment?.substringAfter(':') ?: it)
        } ?: getString(R.string.folder_auto)

        val counts = store.counts()
        queue.text = getString(
            R.string.queue_status,
            counts[CallState.NEW] ?: 0,
            counts[CallState.READY] ?: 0,
            counts[CallState.UPLOADED] ?: 0,
            counts[CallState.FAILED] ?: 0,
        )
        val last = prefs.lastSyncAt
        lastSync.text = getString(R.string.last_sync, if (last == 0L) getString(R.string.never) else timeAgo(last))
        val err = prefs.lastError
        lastError.visibility = if (err.isNullOrBlank()) View.GONE else View.VISIBLE
        lastError.text = getString(R.string.last_error, err.orEmpty())
    }

    private fun chooseAudioSource() {
        val names = resources.getStringArray(R.array.audio_source_names)
        val current = RecordingService.SOURCES.indexOf(prefs.audioSource).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_source_title)
            .setSingleChoiceItems(names, current) { dialog, which ->
                prefs.audioSource = RecordingService.SOURCES[which]
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    /** "۵ دقیقه پیش" in Persian regardless of the phone's language. */
    private fun timeAgo(millis: Long): String {
        val fa = NumberFormat.getInstance(Locale.forLanguageTag("fa"))
        val min = (System.currentTimeMillis() - millis) / 60_000
        return when {
            min < 1 -> getString(R.string.just_now)
            min < 60 -> getString(R.string.minutes_ago, fa.format(min))
            min < 24 * 60 -> getString(R.string.hours_ago, fa.format(min / 60))
            else -> getString(R.string.days_ago, fa.format(min / (24 * 60)))
        }
    }

    /** Does this phone record calls where we can find them? Checks the last 7 days locally. */
    private fun runCompatibilityCheck() {
        if (!granted(Manifest.permission.READ_CALL_LOG)) {
            compatResult.visibility = View.VISIBLE
            compatResult.text = getString(R.string.need_call_log)
            return
        }
        checkCompat.isEnabled = false
        checkCompat.setText(R.string.checking)
        lifecycleScope.launch {
            val (total, matched) = withContext(Dispatchers.IO) { Diagnostics.recentRecordings(this@MainActivity, prefs) }
            checkCompat.isEnabled = true
            checkCompat.setText(R.string.check_compat)
            compatResult.visibility = View.VISIBLE
            compatResult.text = when {
                total == 0 -> getString(R.string.compat_none)
                else -> getString(R.string.compat_result, total, matched) + "\n" + getString(
                    when (matched) {
                        total -> R.string.compat_full
                        0 -> R.string.compat_zero
                        else -> R.string.compat_partial
                    }
                )
            }
        }
    }
}
