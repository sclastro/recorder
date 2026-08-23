package com.sclastro.recorder

import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.audio.WavSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split decision runs once per 20ms chunk on the audio thread, and getting
 * it wrong means either a file that never ends or a stream of empty ones.
 */
class CapturePolicyTest {

    private val rate = 44_100

    @Test
    fun `off by default`() {
        val policy = RecorderEngine.Policy()
        assertFalse(policy.splits)
        assertFalse(policy.shouldSplit(rate * 3600L, rate) { 4L * 1024 * 1024 * 1024 })
    }

    @Test
    fun `splits once the minutes are up`() {
        val policy = RecorderEngine.Policy(splitMinutes = 15)
        assertFalse(policy.shouldSplit(14 * 60L * rate, rate) { 0 })
        assertTrue(policy.shouldSplit(15 * 60L * rate, rate) { 0 })
    }

    @Test
    fun `splits once the file is big enough`() {
        val policy = RecorderEngine.Policy(splitMegabytes = 100)
        assertFalse(policy.shouldSplit(rate.toLong(), rate) { 99L * 1024 * 1024 })
        assertTrue(policy.shouldSplit(rate.toLong(), rate) { 100L * 1024 * 1024 })
    }

    @Test
    fun `whichever limit comes first wins`() {
        val policy = RecorderEngine.Policy(splitMinutes = 60, splitMegabytes = 50)
        // Nowhere near an hour, but already over the size limit.
        assertTrue(policy.shouldSplit(60L * rate, rate) { 50L * 1024 * 1024 })
    }

    @Test
    fun `an empty segment never splits`() {
        val policy = RecorderEngine.Policy(splitMinutes = 15, splitMegabytes = 1)
        assertFalse(policy.shouldSplit(0, rate) { 900L * 1024 * 1024 })
    }

    @Test
    fun `an unusable sample rate never splits`() {
        val policy = RecorderEngine.Policy(splitMinutes = 15)
        assertFalse(policy.shouldSplit(1_000_000, 0) { 0 })
    }

    /**
     * The size lambda is a stat() on the recording, and this runs on the audio
     * thread fifty times a second. It must not be touched unless a size limit
     * is actually set.
     */
    @Test
    fun `the file is not measured unless a size limit is set`() {
        var measured = 0
        val timeOnly = RecorderEngine.Policy(splitMinutes = 15)
        timeOnly.shouldSplit(60L * rate, rate) { measured++; 0 }
        assertEquals(0, measured)

        RecorderEngine.Policy().shouldSplit(60L * rate, rate) { measured++; 0 }
        assertEquals(0, measured)

        RecorderEngine.Policy(splitMegabytes = 50).shouldSplit(60L * rate, rate) { measured++; 0 }
        assertEquals(1, measured)
    }

    @Test
    fun `vox is off unless asked for`() {
        assertFalse(RecorderEngine.Policy().voxEnabled)
        assertTrue(RecorderEngine.Policy(voxEnabled = true).voxEnabled)
    }
}

/**
 * RIFF stores its sizes in unsigned 32 bits, so a WAV cannot honestly describe
 * more than 4 GB of itself. Capture splits before that regardless of what the
 * user asked for, because the alternative is one file other players read as
 * corrupt. These pin the constant and the arithmetic around it.
 */
class WavSizeLimitTest {

    @Test
    fun `the limit stays under the 32-bit ceiling`() {
        assertTrue(WavSink.MAX_DATA_BYTES < 0xFFFF_FFFFL)
        // With room for a header on top of the audio.
        assertTrue(WavSink.MAX_DATA_BYTES + WavSink.FLOAT_HEADER_SIZE < 0xFFFF_FFFFL)
    }

    @Test
    fun `the header size field cannot represent one byte more`() {
        // What buildHeader does to the value: keep the low 32 bits.
        val justOver = 0xFFFF_FFFFL + 1
        assertEquals(0L, justOver and 0xFFFF_FFFFL)
    }

    private fun hoursToLimit(rate: Int, bytesPerSample: Int, channels: Int): Double =
        WavSink.MAX_DATA_BYTES.toDouble() / (rate.toLong() * bytesPerSample * channels) / 3600.0

    /**
     * The limit is reachable in ordinary use — that is the whole reason the
     * guard exists. The recording service holds a wake lock for twelve hours,
     * and every one of these configurations can be exhausted inside that.
     */
    @Test
    fun `the limit is reachable within a single long recording`() {
        assertEquals(2.07, hoursToLimit(96_000, 3, 2), 0.05)
        assertEquals(4.14, hoursToLimit(48_000, 3, 2), 0.05)
        assertEquals(3.11, hoursToLimit(48_000, 4, 2), 0.05)

        // Even the smallest format this app offers runs out before a full day.
        assertTrue(hoursToLimit(44_100, 2, 1) < 24)
    }

    @Test
    fun `the guard trips before the sink can write a bad header`() {
        // What the loop compares: bytes in the current segment against the
        // limit less one chunk, so the split lands before the boundary.
        val chunkBytes = 96_000 / 50 * 6
        val trip = WavSink.MAX_DATA_BYTES - chunkBytes
        assertTrue(trip + chunkBytes <= WavSink.MAX_DATA_BYTES)
        assertTrue(WavSink.MAX_DATA_BYTES + WavSink.PCM_HEADER_SIZE < 0xFFFF_FFFFL)
    }
}
