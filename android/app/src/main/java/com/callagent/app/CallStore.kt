package com.callagent.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object CallState {
    const val NEW = "new"            // waiting for its recording to appear
    const val READY = "ready"        // recording found (or given up on), waiting to upload
    const val UPLOADED = "uploaded"
    const val FAILED = "failed"      // rejected by the server, won't be retried
}

data class PendingCall(
    val id: String,
    val direction: String,
    val number: String,
    val startedAt: Long,
    val durationSec: Int,
    val state: String,
    val audioUri: String? = null,
    val audioName: String? = null,
    val audioSize: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    val endedAt: Long get() = startedAt + durationSec * 1000L
}

/** Local queue of calls, so nothing is lost while the server is out of reach. */
class CallStore(context: Context) :
    SQLiteOpenHelper(context, "calls.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE calls (
                id TEXT PRIMARY KEY,
                direction TEXT NOT NULL,
                number TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                duration_sec INTEGER NOT NULL,
                state TEXT NOT NULL,
                audio_uri TEXT,
                audio_name TEXT,
                audio_size INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX calls_state ON calls(state, started_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Returns true if the call was new. */
    fun insertIfAbsent(c: PendingCall): Boolean {
        val v = ContentValues().apply {
            put("id", c.id)
            put("direction", c.direction)
            put("number", c.number)
            put("started_at", c.startedAt)
            put("duration_sec", c.durationSec)
            put("state", c.state)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("calls", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun byState(state: String, limit: Int = 200): List<PendingCall> =
        readableDatabase.query(
            "calls", null, "state = ?", arrayOf(state), null, null, "started_at ASC", limit.toString()
        ).use { c -> generateSequence { if (c.moveToNext()) c.toCall() else null }.toList() }

    fun usedAudioUris(): Set<String> =
        readableDatabase.rawQuery("SELECT audio_uri FROM calls WHERE audio_uri IS NOT NULL", null)
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }

    fun markReady(id: String, audio: AudioFile?) = update(id) {
        put("state", CallState.READY)
        put("audio_uri", audio?.id)
        put("audio_name", audio?.name)
        put("audio_size", audio?.size ?: 0L)
    }

    fun markUploaded(id: String) = update(id) {
        put("state", CallState.UPLOADED)
        putNull("last_error")
    }

    fun markFailed(id: String, error: String) = update(id) {
        put("state", CallState.FAILED)
        put("last_error", error)
    }

    fun recordAttempt(id: String, error: String) {
        writableDatabase.execSQL(
            "UPDATE calls SET attempts = attempts + 1, last_error = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any>(error, System.currentTimeMillis(), id),
        )
    }

    fun counts(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT state, COUNT(*) FROM calls GROUP BY state", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    /** Uploaded rows are kept a while only to recognise calls we have already seen. */
    fun purgeUploadedBefore(millis: Long) {
        writableDatabase.delete("calls", "state = ? AND started_at < ?", arrayOf(CallState.UPLOADED, millis.toString()))
    }

    private fun update(id: String, fill: ContentValues.() -> Unit) {
        val v = ContentValues().apply(fill).apply { put("updated_at", System.currentTimeMillis()) }
        writableDatabase.update("calls", v, "id = ?", arrayOf(id))
    }

    private fun Cursor.toCall() = PendingCall(
        id = getString(getColumnIndexOrThrow("id")),
        direction = getString(getColumnIndexOrThrow("direction")),
        number = getString(getColumnIndexOrThrow("number")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        durationSec = getInt(getColumnIndexOrThrow("duration_sec")),
        state = getString(getColumnIndexOrThrow("state")),
        audioUri = getString(getColumnIndexOrThrow("audio_uri")),
        audioName = getString(getColumnIndexOrThrow("audio_name")),
        audioSize = getLong(getColumnIndexOrThrow("audio_size")),
        attempts = getInt(getColumnIndexOrThrow("attempts")),
        lastError = getString(getColumnIndexOrThrow("last_error")),
    )
}
