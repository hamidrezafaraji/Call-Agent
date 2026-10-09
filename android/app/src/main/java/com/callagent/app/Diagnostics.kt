package com.callagent.app

import android.content.Context

object Diagnostics {
    private const val WINDOW_MS = 7L * 24 * 3_600_000L

    /**
     * How many answered calls of the last 7 days have a findable recording.
     * Runs only on the phone; nothing is uploaded.
     */
    fun recentRecordings(context: Context, prefs: Prefs): Pair<Int, Int> {
        val since = System.currentTimeMillis() - WINDOW_MS
        val calls = CallLogReader.readSince(context, since).filter { it.durationSec > 0 }
        if (calls.isEmpty()) return 0 to 0
        val files = RecordingFinder(context, prefs).filesSince(since - RecordingMatcher.BEFORE_START_MS)
        val used = mutableSetOf<String>()
        var matched = 0
        for (c in calls) {
            val call = PendingCall("", c.direction, c.number, c.startedAt, c.durationSec, CallState.NEW)
            RecordingMatcher.bestMatch(call, files, used)?.let {
                used += it.id
                matched++
            }
        }
        return calls.size to matched
    }
}
