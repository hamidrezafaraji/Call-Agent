package com.callagent.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Consent + activation by scanning the QR from the server's /admin page (or typing it). */
class SetupActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var consent: CheckBox
    private lateinit var server: EditText
    private lateinit var code: EditText
    private lateinit var scan: Button
    private lateinit var activate: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val qr = QrPayload.parse(text)
        if (qr == null) {
            showError(getString(R.string.bad_qr))
            return@registerForActivityResult
        }
        server.setText(qr.server)
        code.setText(qr.code)
        activate(qr.server, qr.code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        padForSystemBars(findViewById(R.id.root))
        prefs = Prefs(this)
        consent = findViewById(R.id.consent)
        server = findViewById(R.id.server)
        code = findViewById(R.id.code)
        scan = findViewById(R.id.scan)
        activate = findViewById(R.id.activate)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        consent.isChecked = prefs.consentAccepted
        prefs.serverUrl?.let { server.setText(it) }

        scan.setOnClickListener {
            if (!checkConsent()) return@setOnClickListener
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
        activate.setOnClickListener {
            val url = normalizeServer(server.text.toString())
            val c = code.text.toString().trim()
            if (url.isEmpty() || c.isEmpty()) showError(getString(R.string.fill_fields)) else activate(url, c)
        }
    }

    private fun normalizeServer(input: String): String {
        val s = input.trim().trimEnd('/')
        if (s.isEmpty()) return s
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
    }

    private fun checkConsent(): Boolean {
        if (!consent.isChecked) showError(getString(R.string.consent_required))
        return consent.isChecked
    }

    private fun activate(serverUrl: String, activationCode: String) {
        if (!checkConsent()) return
        prefs.consentAccepted = true
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Api(serverUrl).activate(activationCode) }
            }
            setBusy(false)
            result.onSuccess {
                prefs.saveActivation(serverUrl, it, System.currentTimeMillis())
                SyncWorker.schedule(this@SetupActivity)
                startActivity(Intent(this@SetupActivity, MainActivity::class.java))
                finish()
            }.onFailure {
                showError(getString(R.string.activation_failed, describeError(this@SetupActivity, it)))
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        scan.isEnabled = !busy
        activate.isEnabled = !busy
        if (busy) error.visibility = View.GONE
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }
}
