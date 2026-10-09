package com.callagent.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class RecordingMatcherTest {
    private val start = 1_760_000_000_000L // some fixed moment
    private val call = PendingCall("c1", "incoming", "09124512597", start, 95, CallState.NEW)

    private fun file(
        id: String,
        modifiedAfterEndSec: Long = 2,
        durationSec: Long = 95,
        name: String = "Call recording 241009_103000.m4a",
        path: String = "Recordings/Call/",
    ) = AudioFile(id, name, path, call.endedAt + modifiedAfterEndSec * 1000, durationSec * 1000, 1000)

    @Test
    fun picksRecordingFinishedAtCallEnd() {
        val match = RecordingMatcher.bestMatch(call, listOf(file("a")), emptySet())
        assertEquals("a", match?.id)
    }

    @Test
    fun prefersClosestInTimeAndLength() {
        val files = listOf(file("far", modifiedAfterEndSec = 200), file("near", modifiedAfterEndSec = 3))
        assertEquals("near", RecordingMatcher.bestMatch(call, files, emptySet())?.id)
    }

    @Test
    fun rejectsWrongLength() {
        assertNull(RecordingMatcher.bestMatch(call, listOf(file("x", durationSec = 300)), emptySet()))
    }

    @Test
    fun rejectsFilesOutsideTimeWindow() {
        assertNull(RecordingMatcher.bestMatch(call, listOf(file("x", modifiedAfterEndSec = 3600)), emptySet()))
    }

    @Test
    fun ignoresMusicAndVoiceNotes() {
        val song = file("song", name = "track01.mp3", path = "Music/")
        assertNull(RecordingMatcher.bestMatch(call, listOf(song), emptySet()))
    }

    @Test
    fun acceptsFileNamedWithTheNumberInAnyFolder() {
        val f = file("n", name = "09124512597_20261009.amr", path = "Sounds/")
        assertEquals("n", RecordingMatcher.bestMatch(call, listOf(f), emptySet())?.id)
    }

    @Test
    fun acceptsAnythingInUserPickedFolder() {
        val f = file("p", name = "0001.m4a", path = "").copy(fromPickedFolder = true)
        assertEquals("p", RecordingMatcher.bestMatch(call, listOf(f), emptySet())?.id)
    }

    @Test
    fun neverReusesAFileAlreadyAttachedToAnotherCall() {
        assertNull(RecordingMatcher.bestMatch(call, listOf(file("a")), setOf("a")))
    }

    @Test
    fun unknownDurationStillMatchesOnTime() {
        assertEquals("u", RecordingMatcher.bestMatch(call, listOf(file("u", durationSec = 0)), emptySet())?.id)
    }

    @Test
    fun onlyServerSupportedExtensions() {
        assertEquals(true, RecordingMatcher.hasAudioExtension("a.M4A"))
        assertEquals(false, RecordingMatcher.hasAudioExtension("notes.txt"))
    }

    @Test
    fun isoTimeKeepsSecondsAndOffset() {
        val tehran = ZoneId.of("Asia/Tehran")
        // 2026-10-09 07:00:00 UTC = 10:30:00 in Tehran
        assertEquals("2026-10-09T10:30:00+03:30", Api.isoTime(1_791_529_200_000L, tehran))
    }

    @Test
    fun parsesActivationQr() {
        val qr = QrPayload.parse("""{"v":1,"server":"http://192.168.1.4:8100/","code":"ABCD-EFGH"}""")
        assertEquals(QrPayload("http://192.168.1.4:8100", "ABCD-EFGH"), qr)
        assertNull(QrPayload.parse("https://example.com"))
    }

    private fun own(id: String, startOffsetSec: Long) =
        AudioFile(id, "rec.m4a", "", call.endedAt, 0, 1000, ownStartedAt = start + startOffsetSec * 1000)

    @Test
    fun ownRecordingMatchedByStartTimeEvenWithRingingIncluded() {
        // incoming call: recording starts when answered, 20s after the log's start time
        assertEquals("o", RecordingMatcher.bestMatch(call, listOf(own("o", 20)), emptySet())?.id)
    }

    @Test
    fun ownRecordingPreferredOverPhoneRecorderFile() {
        val files = listOf(file("phone"), own("o", 1))
        assertEquals("o", RecordingMatcher.bestMatch(call, files, emptySet())?.id)
    }

    @Test
    fun ownRecordingOfAnotherCallIsNotUsed() {
        assertNull(RecordingMatcher.bestMatch(call, listOf(own("later", 600)), emptySet()))
    }

    @Test
    fun picksTheOwnRecordingClosestToTheCallStart() {
        val files = listOf(own("a", 60), own("b", 2))
        assertEquals("b", RecordingMatcher.bestMatch(call, files, emptySet())?.id)
    }
}
