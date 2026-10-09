package com.callagent.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        checkCompat = findViewById(R.id.check_compat)
        compatResult = findViewById(R.id.compat_result)
        folder = findViewById(R.id.folder)
        queue = findViewById(R.id.queue)
        lastSync = findViewById(R.id.last_sync)
        lastError = findViewById(R.id.last_error)

        permCallLog.setOnClickListener { ask(Manifest.permission.READ_CALL_LOG) }
        permAudio.setOnClickListener { ask(RecordingFinder.AUDIO_PERMISSION) }
        permBattery.setOnClickListener { askBatteryExemption() }
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
        lastSync.text = getString(
            R.string.last_sync,
            if (last == 0L) getString(R.string.never)
            else DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        )
        val err = prefs.lastError
        lastError.visibility = if (err.isNullOrBlank()) View.GONE else View.VISIBLE
        lastError.text = getString(R.string.last_error, err.orEmpty())
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
