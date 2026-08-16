package com.sclastro.recorder

import com.sclastro.recorder.data.Recording
import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Test

/**
 * The library draws a part-listened marker from this, so the two edge cases
 * matter: barely started and effectively finished both have to read as zero,
 * matching the rule playback resumes by.
 */
class ListenedFractionTest {

    private fun recording(durationMs: Long, lastPositionMs: Long) = Recording(
        id = 1,
        displayName = "Test",
        folder = "",
        file = File("/tmp/test.wav"),
        relPath = "test.wav",
        createdAt = 0,
        durationMs = durationMs,
        sizeBytes = 0,
        format = "WAV",
        sampleRate = 44_100,
        bitDepth = 16,
        channels = 1,
        favorite = false,
        note = "",
        bookmarks = emptyList(),
        lastPositionMs = lastPositionMs,
        deletedAt = null,
    )

    @Test
    fun `halfway through reads as half`() {
        assertEquals(0.5f, recording(120_000, 60_000).listenedFraction, 0.001f)
    }

    @Test
    fun `never played reads as zero`() {
        assertEquals(0f, recording(120_000, 0).listenedFraction, 0.001f)
    }

    @Test
    fun `first three seconds do not count as started`() {
        assertEquals(0f, recording(120_000, 2_500).listenedFraction, 0.001f)
    }

    @Test
    fun `last three seconds count as finished`() {
        assertEquals(0f, recording(120_000, 118_000).listenedFraction, 0.001f)
    }

    @Test
    fun `unknown duration reads as zero`() {
        assertEquals(0f, recording(0, 10_000).listenedFraction, 0.001f)
    }

    @Test
    fun `position past the end is clamped away`() {
        assertEquals(0f, recording(120_000, 500_000).listenedFraction, 0.001f)
    }
}
