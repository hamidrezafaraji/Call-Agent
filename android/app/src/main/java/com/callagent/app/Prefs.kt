package com.callagent.app

import android.content.Context
import androidx.core.content.edit

/** Small persistent settings: activation, sync bookkeeping, last error. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("callagent", Context.MODE_PRIVATE)

    var consentAccepted: Boolean
        get() = sp.getBoolean("consent", false)
        set(v) = sp.edit { putBoolean("consent", v) }

    var serverUrl: String?
        get() = sp.getString("server", null)
        set(v) = sp.edit { putString("server", v) }

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit { putString("token", v) }

    var deviceId: String?
        get() = sp.getString("device_id", null)
        set(v) = sp.edit { putString("device_id", v) }

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(v) = sp.edit { putString("device_name", v) }

    /** Calls that started before this moment (activation) are never collected. */
    var activatedAt: Long
        get() = sp.getLong("activated_at", 0L)
        set(v) = sp.edit { putLong("activated_at", v) }

    /** Start time of the newest call already read from the call log. */
    var newestCallSeen: Long
        get() = sp.getLong("newest_call", 0L)
        set(v) = sp.edit { putLong("newest_call", v) }

    /** Optional folder picked by the user (SAF tree URI) when auto-detection finds nothing. */
    var recordingsTreeUri: String?
        get() = sp.getString("recordings_tree", null)
        set(v) = sp.edit { putString("recordings_tree", v) }

    /** MediaRecorder.AudioSource used for in-app call recording (6 = VOICE_RECOGNITION). */
    var audioSource: Int
        get() = sp.getInt("audio_source", 6)
        set(v) = sp.edit { putInt("audio_source", v) }

    var lastSyncAt: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit { putLong("last_sync", v) }

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit { putString("last_error", v) }

    /** Set when the server rejects our token: the admin revoked this phone. */
    var revoked: Boolean
        get() = sp.getBoolean("revoked", false)
        set(v) = sp.edit { putBoolean("revoked", v) }

    val isActivated: Boolean get() = token != null && serverUrl != null && !revoked

    fun saveActivation(server: String, result: ActivationResult, now: Long) = sp.edit {
        putString("server", server)
        putString("token", result.token)
        putString("device_id", result.deviceId)
        putString("device_name", result.name)
        putLong("activated_at", now)
        putLong("newest_call", now)
        putBoolean("revoked", false)
        remove("last_error")
    }

    fun clearActivation() = sp.edit {
        remove("token")
        remove("device_id")
        remove("device_name")
        putBoolean("revoked", false)
    }
}
