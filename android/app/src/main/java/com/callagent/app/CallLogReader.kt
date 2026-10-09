package com.callagent.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

data class LoggedCall(
    val logId: Long,
    val number: String,
    val startedAt: Long,
    val durationSec: Int,
    val direction: String,
)

/** Reads the phone's call log (same API on every brand). */
object CallLogReader {
    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    private fun direction(type: Int): String? = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> OUTGOING
        CallLog.Calls.INCOMING_TYPE,
        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.REJECTED_TYPE,
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> INCOMING
        else -> null // voicemail, blocked
    }

    /** Calls that started after [sinceMillis], oldest first. */
    fun readSince(context: Context, sinceMillis: Long): List<LoggedCall> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        val out = mutableListOf<LoggedCall>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(sinceMillis.toString()),
            "${CallLog.Calls.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val dir = direction(c.getInt(4)) ?: continue
                out += LoggedCall(
                    logId = c.getLong(0),
                    number = c.getString(1)?.trim().orEmpty().ifEmpty { "private" },
                    startedAt = c.getLong(2),
                    durationSec = c.getInt(3),
                    direction = dir,
                )
            }
        }
        return out
    }
}
