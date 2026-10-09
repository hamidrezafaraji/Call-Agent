package com.callagent.app

import android.content.Context
import android.net.Uri
import android.provider.Settings
import java.io.File

/**
 * Recordings made by this app (RecordingService). File names carry the start time:
 * rec_<startMillis>.m4a, written as .part while the call is still going on.
 */
object OwnRecordings {
    private const val PREFIX = "rec_"
    const val EXT = ".m4a"
    const val PART = ".part"
    private const val KEEP_MS = 14L * 24 * 3_600_000L

    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newPartFile(context: Context, startedAt: Long) = File(dir(context), "$PREFIX$startedAt$EXT$PART")

    fun list(context: Context, sinceMillis: Long): List<AudioFile> =
        dir(context).listFiles().orEmpty()
            .filter { it.name.startsWith(PREFIX) && it.name.endsWith(EXT) && it.lastModified() >= sinceMillis }
            .mapNotNull { f ->
                val start = f.name.removePrefix(PREFIX).removeSuffix(EXT).toLongOrNull() ?: return@mapNotNull null
                AudioFile(
                    id = Uri.fromFile(f).toString(),
                    name = f.name,
                    path = "",
                    modifiedAt = f.lastModified(),
                    durationMs = 0,
                    size = f.length(),
                    ownStartedAt = start,
                )
            }

    /** After upload the server has the audio; free the phone's storage. */
    fun deleteIfOwn(context: Context, uri: String?) {
        val path = uri?.let(Uri::parse)?.takeIf { it.scheme == "file" }?.path ?: return
        val f = File(path)
        if (f.parentFile == dir(context)) f.delete()
    }

    /** Leftovers: unfinished files from a crash, and recordings no call ever claimed. */
    fun cleanup(context: Context, now: Long = System.currentTimeMillis()) {
        dir(context).listFiles().orEmpty()
            .filter { now - it.lastModified() > if (it.name.endsWith(PART)) 86_400_000L else KEEP_MS }
            .forEach { it.delete() }
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.startsWith("${context.packageName}/") && it.endsWith("CallAccessibilityService")
        }
    }
}
