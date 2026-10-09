package com.callagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/** Records the microphone for the length of a call (foreground service, as Android requires). */
class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var partFile: File? = null

    companion object {
        private const val TAG = "CallAgentRec"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1

        /** Audio sources the user can pick; which one hears the other side best depends on the phone. */
        val SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_CALL,
        )

        fun start(context: Context) = send(context, ACTION_START)

        fun stop(context: Context) {
            if (isRunning) send(context, ACTION_STOP)
        }

        @Volatile
        var isRunning = false
            private set

        private fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            try {
                if (action == ACTION_START) ContextCompat.startForegroundService(context, intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "cannot $action recording", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            else -> {
                finish()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.rec_notification))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun begin() {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            Log.w(TAG, "foreground start refused", e)
            stopSelf()
            return
        }
        if (recorder != null) return
        isRunning = true

        val startedAt = System.currentTimeMillis()
        val file = OwnRecordings.newPartFile(this, startedAt)
        val preferred = Prefs(this).audioSource
        // the user's choice first, then the sources that work on most phones
        val order = (listOf(preferred) + listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)).distinct()
        for (source in order) {
            val r = newRecorder()
            try {
                r.setAudioSource(source)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(16_000) // plenty for speech; Whisper works at 16 kHz
                r.setAudioEncodingBitRate(32_000)
                r.setOutputFile(file.path)
                r.prepare()
                r.start()
                recorder = r
                partFile = file
                Log.i(TAG, "recording with source $source")
                return
            } catch (e: Exception) {
                Log.w(TAG, "source $source failed", e)
                r.release()
                file.delete()
            }
        }
        isRunning = false
        stopSelf()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

    private fun finish() {
        val r = recorder ?: return
        recorder = null
        isRunning = false
        val ok = try {
            r.stop()
            true
        } catch (e: Exception) {
            false // stopped right after starting: no valid audio
        } finally {
            r.release()
        }
        val part = partFile ?: return
        partFile = null
        if (ok && part.length() > 0) {
            part.renameTo(File(part.parentFile, part.name.removeSuffix(OwnRecordings.PART)))
        } else {
            part.delete()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        SyncWorker.syncNow(this, delaySec = 15)
    }
}
