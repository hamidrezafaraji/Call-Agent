package com.callagent.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/** Status screen: permissions, recording mode and compatibility, upload queue. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var store: CallStore
    private val permissions = PermissionFlow(this) { refresh() }
    private val fa = NumberFormat.getInstance(Locale.forLanguageTag("fa"))

    private lateinit var title: TextView
    private lateinit var server: TextView
    private lateinit var revokedBox: View
    private lateinit var permStatus: TextView
    private lateinit var fixPermissions: Button
    private lateinit var recMode: TextView
    private lateinit var checkCompat: Button
    private lateinit var audioSource: Button
    private lateinit var folder: TextView
    private lateinit var queue: TextView
    private lateinit var lastSync: TextView
    private lateinit var lastError: TextView
    private lateinit var syncNow: Button

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
        padForSystemBars(findViewById(R.id.root))
        store = CallStore(this)

        title = findViewById(R.id.title)
        server = findViewById(R.id.server)
        revokedBox = findViewById(R.id.revoked_box)
        permStatus = findViewById(R.id.perm_status)
        fixPermissions = findViewById(R.id.fix_permissions)
        recMode = findViewById(R.id.rec_mode)
        checkCompat = findViewById(R.id.check_compat)
        audioSource = findViewById(R.id.audio_source)
        folder = findViewById(R.id.folder)
        queue = findViewById(R.id.queue)
        lastSync = findViewById(R.id.last_sync)
        lastError = findViewById(R.id.last_error)
        syncNow = findViewById(R.id.sync_now)

        fixPermissions.setOnClickListener { permissions.start() }
        checkCompat.setOnClickListener { runCompatibilityCheck() }
        audioSource.setOnClickListener { chooseAudioSource() }
        findViewById<Button>(R.id.pick_folder).setOnClickListener { folderPicker.launch(null) }
        syncNow.setOnClickListener { runSyncNow() }
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

        if (!prefs.revoked) permissions.start()
    }

    private fun refresh() {
        if (!::title.isInitialized) return
        title.text = getString(R.string.activated_as, prefs.deviceName.orEmpty())
        server.text = getString(R.string.server_label, prefs.serverUrl.orEmpty())
        revokedBox.visibility = if (prefs.revoked) View.VISIBLE else View.GONE

        val missing = permissions.missing()
        if (missing.isEmpty()) {
            permStatus.text = getString(R.string.perm_all_ok)
            permStatus.setTextColor(getColor(R.color.ok))
            fixPermissions.visibility = View.GONE
        } else {
            permStatus.text = getString(R.string.perm_missing, missing.joinToString("، ") { getString(it.title) })
            permStatus.setTextColor(getColor(R.color.warn))
            fixPermissions.visibility = View.VISIBLE
        }

        recMode.setText(if (OwnRecordings.accessibilityEnabled(this)) R.string.rec_mode_app else R.string.rec_mode_phone)
        val names = resources.getStringArray(R.array.audio_source_names)
        val idx = RecordingService.SOURCES.indexOf(prefs.audioSource).coerceAtLeast(0)
        audioSource.text = getString(R.string.audio_source, names[idx])

        folder.text = prefs.recordingsTreeUri?.let {
            getString(R.string.folder_label, Uri.parse(it).lastPathSegment?.substringAfter(':') ?: it)
        } ?: getString(R.string.folder_auto)

        val counts = store.counts()
        queue.text = getString(
            R.string.queue_status,
            fa.format(counts[CallState.NEW] ?: 0),
            fa.format(counts[CallState.READY] ?: 0),
            fa.format(counts[CallState.UPLOADED] ?: 0),
            fa.format(counts[CallState.FAILED] ?: 0),
        )
        val last = prefs.lastSyncAt
        lastSync.text = getString(R.string.last_sync, if (last == 0L) getString(R.string.never) else timeAgo(last))
        val err = prefs.lastError
        lastError.visibility = if (err.isNullOrBlank()) View.GONE else View.VISIBLE
        lastError.text = getString(R.string.last_error, err.orEmpty())
    }

    private fun runSyncNow() {
        syncNow.isEnabled = false
        syncNow.setText(R.string.syncing)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Syncer(applicationContext).run() }
            syncNow.isEnabled = true
            syncNow.setText(R.string.sync_now)
            refresh()
            val message = when {
                r.error != null -> getString(R.string.sync_failed, r.error)
                r.newCalls == 0 && r.uploaded == 0 -> getString(R.string.sync_nothing)
                else -> getString(R.string.sync_done, fa.format(r.newCalls), fa.format(r.uploaded))
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (r.error != null) R.string.sync_failed_title else R.string.sync_done_title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    /** Is this phone ready to record calls? Shows the verdict in a popup with the phone's name. */
    private fun runCompatibilityCheck() {
        checkCompat.isEnabled = false
        checkCompat.setText(R.string.checking)
        lifecycleScope.launch {
            val (total, matched) = withContext(Dispatchers.IO) {
                if (CallLogReader.hasPermission(this@MainActivity)) Diagnostics.recentRecordings(this@MainActivity, prefs)
                else 0 to 0
            }
            checkCompat.isEnabled = true
            checkCompat.setText(R.string.check_compat)

            val phone = Diagnostics.phoneName()
            val appRecording = OwnRecordings.accessibilityEnabled(this@MainActivity) &&
                permissions.missing().none { it.title == R.string.p_mic || it.title == R.string.p_phone }
            val phoneRecorder = total > 0 && matched > 0
            val ok = appRecording || (phoneRecorder && matched == total)

            val details = buildString {
                if (appRecording) append(getString(R.string.compat_app_ok))
                if (total > 0) {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(R.string.compat_result, fa.format(total), fa.format(matched)))
                }
                if (!appRecording && !phoneRecorder) {
                    if (isNotEmpty()) append("\n\n")
                    append(getString(R.string.compat_need_setup))
                }
            }
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(if (ok) R.string.compat_ok_title else R.string.compat_bad_title, phone))
                .setMessage(details)
                .setPositiveButton(R.string.ok, null)
            if (!ok && permissions.missing().isNotEmpty()) {
                dialog.setNeutralButton(R.string.fix_permissions) { _, _ -> permissions.start() }
            }
            dialog.show()
        }
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
        val min = (System.currentTimeMillis() - millis) / 60_000
        return when {
            min < 1 -> getString(R.string.just_now)
            min < 60 -> getString(R.string.minutes_ago, fa.format(min))
            min < 24 * 60 -> getString(R.string.hours_ago, fa.format(min / 60))
            else -> getString(R.string.days_ago, fa.format(min / (24 * 60)))
        }
    }
}
