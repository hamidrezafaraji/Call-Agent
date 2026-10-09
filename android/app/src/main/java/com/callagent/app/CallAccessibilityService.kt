@file:Suppress("DEPRECATION") // PhoneStateListener is still needed below Android 12

package com.callagent.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Enabled by the user in Settings > Accessibility. Reads no screen content: it exists because
 * Android lets an app with a bound accessibility service record the microphone from the
 * background. It watches the call state and starts/stops RecordingService.
 */
class CallAccessibilityService : AccessibilityService() {
    private var telephony: TelephonyManager? = null
    private var listener: Any? = null

    override fun onServiceConnected() {
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        listen()
    }

    // Only used to retry listening once the phone-state permission is granted.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (listener == null) listen()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopListening()
        RecordingService.stop(this)
        super.onDestroy()
    }

    private fun onCallState(state: Int) {
        if (!Prefs(this).isActivated) return
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> RecordingService.start(this)
            TelephonyManager.CALL_STATE_IDLE -> RecordingService.stop(this)
        }
    }

    private fun listen() {
        val tm = telephony ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onCallState(state)
            }
            tm.registerTelephonyCallback(mainExecutor, cb)
            listener = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCallState(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            listener = l
        }
    }

    private fun stopListening() {
        val tm = telephony ?: return
        when (val l = listener) {
            is TelephonyCallback -> if (Build.VERSION.SDK_INT >= 31) tm.unregisterTelephonyCallback(l)
            is PhoneStateListener -> @Suppress("DEPRECATION") tm.listen(l, PhoneStateListener.LISTEN_NONE)
        }
        listener = null
    }
}
