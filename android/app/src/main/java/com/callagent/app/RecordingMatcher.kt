package com.callagent.app

import kotlin.math.abs
import kotlin.math.max

/** An audio file on the phone, from the media index or a user-picked folder. */
data class AudioFile(
    val id: String,            // content:// URI
    val name: String,
    val path: String,          // folder, e.g. "Recordings/Call/"
    val modifiedAt: Long,      // millis
    val durationMs: Long,      // 0 = unknown
    val size: Long,
    val fromPickedFolder: Boolean = false,
)

/**
 * Pairs a call with its recording without knowing the phone brand: the recording is
 * finished around the time the call ends and is about as long as the call.
 * Pure Kotlin so it can be unit-tested.
 */
object RecordingMatcher {
    const val BEFORE_START_MS = 60_000L
    const val AFTER_END_MS = 5 * 60_000L

    /** Extensions the server accepts. */
    val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "amr", "wav", "ogg", "opus", "aac", "3gp", "flac", "webm")

    private val KEYWORDS = listOf("call", "rec", "phone", "تماس", "مکالمه")

    fun hasAudioExtension(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    private fun lastDigits(number: String) = number.filter(Char::isDigit).takeLast(7)

    private fun nameHasNumber(f: AudioFile, number: String): Boolean {
        val d = lastDigits(number)
        return d.length == 7 && d in f.name.filter(Char::isDigit)
    }

    /** Keeps music, voice notes, etc. out: folder or file name must hint at a call. */
    fun looksLikeCallRecording(f: AudioFile, number: String): Boolean {
        if (f.fromPickedFolder) return true
        val text = (f.path + "/" + f.name).lowercase()
        return KEYWORDS.any { it in text } || nameHasNumber(f, number)
    }

    fun inTimeWindow(f: AudioFile, call: PendingCall) =
        f.modifiedAt in (call.startedAt - BEFORE_START_MS)..(call.endedAt + AFTER_END_MS)

    fun durationMatches(f: AudioFile, call: PendingCall): Boolean {
        if (f.durationMs <= 0) return true // unknown length
        val diff = abs(f.durationMs / 1000.0 - call.durationSec)
        return diff <= max(5.0, call.durationSec * 0.2)
    }

    private fun score(f: AudioFile, call: PendingCall): Double {
        var s = abs(f.modifiedAt - call.endedAt) / 1000.0
        if (f.durationMs > 0) s += abs(f.durationMs / 1000.0 - call.durationSec) * 2
        if (nameHasNumber(f, call.number)) s -= 600
        return s
    }

    fun bestMatch(call: PendingCall, files: List<AudioFile>, used: Set<String>): AudioFile? =
        files.asSequence()
            .filter { it.id !in used }
            .filter { inTimeWindow(it, call) && durationMatches(it, call) }
            .filter { looksLikeCallRecording(it, call.number) }
            .minByOrNull { score(it, call) }
}
