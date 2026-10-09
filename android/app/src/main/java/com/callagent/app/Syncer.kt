package com.callagent.app

import android.content.Context
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.max

/**
 * One sync pass: read new calls from the call log, attach their recordings, upload.
 * Blocking; run it off the main thread (SyncWorker does).
 */
class Syncer(private val context: Context) {
    private val prefs = Prefs(context)
    private val store = CallStore(context)
    private val finder = RecordingFinder(context, prefs)

    companion object {
        private val lock = Any()

        /** How long to wait for a recording to show up before sending the call without audio. */
        const val RECORDING_GRACE_MS = 30 * 60_000L

        /** Re-read this much of the log each time: a long call is logged only when it ends,
         *  after shorter calls that started later (call waiting). Duplicates are ignored. */
        const val LOOKBACK_MS = 6 * 3_600_000L

        const val KEEP_UPLOADED_MS = 30L * 24 * 3_600_000L
    }

    fun run() {
        synchronized(lock) {
            if (!prefs.isActivated) return
            try {
                collectCalls()
                attachRecordings()
                upload()
                store.purgeUploadedBefore(System.currentTimeMillis() - KEEP_UPLOADED_MS)
                if (!prefs.revoked) prefs.lastError = null
            } catch (e: Exception) {
                prefs.lastError = describeError(context, e)
            } finally {
                prefs.lastSyncAt = System.currentTimeMillis()
            }
        }
    }

    private fun collectCalls() {
        if (!CallLogReader.hasPermission(context)) return
        val activatedAt = prefs.activatedAt
        val since = max(activatedAt, prefs.newestCallSeen - LOOKBACK_MS)
        val prefix = prefs.deviceId.orEmpty().take(8)
        var newest = prefs.newestCallSeen
        for (c in CallLogReader.readSince(context, since)) {
            if (c.startedAt < activatedAt) continue
            store.insertIfAbsent(
                PendingCall(
                    // unique per phone and stable across syncs
                    id = "$prefix-${c.logId}-${c.startedAt}",
                    direction = c.direction,
                    number = c.number,
                    startedAt = c.startedAt,
                    durationSec = c.durationSec,
                    // unanswered calls have nothing to record
                    state = if (c.durationSec > 0) CallState.NEW else CallState.READY,
                )
            )
            newest = max(newest, c.startedAt)
        }
        prefs.newestCallSeen = newest
    }

    private fun attachRecordings() {
        val waiting = store.byState(CallState.NEW)
        if (waiting.isEmpty()) return
        val files = finder.filesSince(waiting.first().startedAt - RecordingMatcher.BEFORE_START_MS)
        val used = store.usedAudioUris().toMutableSet()
        val now = System.currentTimeMillis()
        for (call in waiting) {
            val match = RecordingMatcher.bestMatch(call, files, used)
            when {
                match != null -> {
                    store.markReady(call.id, match)
                    used += match.id
                }
                now - call.endedAt > RECORDING_GRACE_MS -> store.markReady(call.id, null)
                // otherwise the recorder may still be writing the file: try again next pass
            }
        }
    }

    private fun upload() {
        val api = Api(prefs.serverUrl ?: return, prefs.token)
        for (call in store.byState(CallState.READY)) {
            try {
                api.uploadCall(call, context.contentResolver)
                store.markUploaded(call.id)
            } catch (e: ApiException) {
                when (e.code) {
                    401 -> {
                        prefs.revoked = true
                        prefs.lastError = context.getString(R.string.revoked)
                        return
                    }
                    409 -> store.markUploaded(call.id) // server already has it
                    400, 404, 413, 422 -> store.markFailed(call.id, "${e.code}: ${e.message}")
                    else -> {
                        store.recordAttempt(call.id, e.message.orEmpty())
                        throw e // server trouble: stop until next pass
                    }
                }
            } catch (e: FileNotFoundException) {
                store.markReady(call.id, null) // recording was deleted: send the call without it
            } catch (e: SecurityException) {
                store.markReady(call.id, null) // access to the recording was withdrawn
            } catch (e: IOException) {
                store.recordAttempt(call.id, e.message.orEmpty())
                throw e // offline: stop until next pass
            }
        }
    }
}

fun describeError(context: Context, e: Throwable): String = when (e) {
    is ApiException -> context.getString(R.string.err_server, e.code, e.message)
    is UnknownHostException, is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
        context.getString(R.string.err_unreachable)
    else -> e.message ?: e.javaClass.simpleName
}
