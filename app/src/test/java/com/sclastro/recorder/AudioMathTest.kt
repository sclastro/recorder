package com.sclastro.recorder

import com.sclastro.recorder.audio.AudioContainer
import com.sclastro.recorder.audio.BitDepth
import com.sclastro.recorder.audio.PcmMath
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.audio.RecordingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMathTest {

    @Test
    fun `peak of full scale 16 bit is one`() {
        val buffer = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x00)
        val (peak, _) = PcmMath.analyse(buffer, buffer.size, BitDepth.PCM_16)
        assertEquals(1f, peak, 0.001f)
    }

    @Test
    fun `silence floors at minus sixty dB`() {
        assertEquals(-60f, PcmMath.toDb(0f), 0.001f)
        assertEquals(0f, PcmMath.dbToFraction(-60f), 0.001f)
        assertEquals(1f, PcmMath.dbToFraction(0f), 0.001f)
    }

    @Test
    fun `half amplitude is about minus six dB`() {
        assertEquals(-6.02f, PcmMath.toDb(0.5f), 0.05f)
    }

    @Test
    fun `peak recorder emits one bucket per fifty milliseconds`() {
        val sampleRate = 8000
        val recorder = Peaks.Recorder(sampleRate, BitDepth.PCM_16, 1)
        val bytesPerBucket = sampleRate * Peaks.BUCKET_MS / 1000 * 2
        repeat(4) { recorder.feed(1f, bytesPerBucket) }
        assertEquals(4, recorder.snapshot().size)
        assertEquals(255, recorder.snapshot()[0].toInt() and 0xFF)
    }

    @Test
    fun `resampling produces the requested column count`() {
        val peaks = ByteArray(1000) { (it % 256).toByte() }
        val columns = Peaks.resample(peaks, 40)
        assertEquals(40, columns.size)
        assertTrue(columns.all { it in 0f..1f })
    }

    @Test
    fun `compressed containers are forced to sixteen bit`() {
        val config = RecordingConfig(
            bitDepth = BitDepth.FLOAT_32,
            container = AudioContainer.M4A,
        ).normalised()
        assertEquals(BitDepth.PCM_16, config.bitDepth)
    }

    @Test
    fun `wav keeps the requested bit depth`() {
        val config = RecordingConfig(
            bitDepth = BitDepth.FLOAT_32,
            container = AudioContainer.WAV,
        ).normalised()
        assertEquals(BitDepth.FLOAT_32, config.bitDepth)
    }

    @Test
    fun `wav size estimate follows sample rate and depth`() {
        val config = RecordingConfig(48000, BitDepth.PCM_24, com.sclastro.recorder.audio.Channels.STEREO, AudioContainer.WAV)
        assertEquals(48000L * 3 * 2, config.bytesPerSecond())
    }
}

/**
 * The peak recorder runs on the capture thread, so it has to keep its buckets
 * without reallocating a large structure under it. These pin the bucketing
 * arithmetic and the growth.
 */
class PeakRecorderTest {

    private val rate = 44_100

    @Test
    fun `one bucket per fifty milliseconds of audio`() {
        val recorder = Peaks.Recorder(rate, BitDepth.PCM_16, 1)
        val bytesPerBucket = rate * Peaks.BUCKET_MS / 1000 * 2
        // Exactly ten buckets' worth, fed in one go.
        recorder.feed(1f, bytesPerBucket * 10)
        assertEquals(10, recorder.snapshot().size)
    }

    @Test
    fun `a partial bucket is not emitted until it fills`() {
        val recorder = Peaks.Recorder(rate, BitDepth.PCM_16, 1)
        val bytesPerBucket = rate * Peaks.BUCKET_MS / 1000 * 2
        recorder.feed(1f, bytesPerBucket - 1)
        assertEquals(0, recorder.snapshot().size)
        recorder.feed(1f, 1)
        assertEquals(1, recorder.snapshot().size)
    }

    @Test
    fun `the bucket keeps the loudest peak it saw`() {
        val recorder = Peaks.Recorder(rate, BitDepth.PCM_16, 1)
        val bytesPerBucket = rate * Peaks.BUCKET_MS / 1000 * 2
        recorder.feed(0.25f, bytesPerBucket / 2)
        recorder.feed(1f, bytesPerBucket / 2)
        recorder.feed(0.1f, bytesPerBucket / 2)
        val snapshot = recorder.snapshot()
        assertEquals(255, snapshot[0].toInt() and 0xFF)
    }

    @Test
    fun `growth past the initial capacity keeps every bucket`() {
        val recorder = Peaks.Recorder(rate, BitDepth.PCM_16, 1)
        val bytesPerBucket = rate * Peaks.BUCKET_MS / 1000 * 2
        // Well past the 4096-bucket starting array, several doublings in.
        repeat(10_000) { recorder.feed(1f, bytesPerBucket) }
        val snapshot = recorder.snapshot()
        assertEquals(10_000, snapshot.size)
        assertTrue(snapshot.all { (it.toInt() and 0xFF) == 255 })
    }

    @Test
    fun `snapshot does not expose the spare capacity`() {
        val recorder = Peaks.Recorder(rate, BitDepth.PCM_16, 1)
        val bytesPerBucket = rate * Peaks.BUCKET_MS / 1000 * 2
        recorder.feed(1f, bytesPerBucket * 3)
        // Three buckets, not the 4096 the backing array actually holds.
        assertEquals(3, recorder.snapshot().size)
    }

    @Test
    fun `a nonsensical rate produces nothing rather than looping`() {
        val recorder = Peaks.Recorder(0, BitDepth.PCM_16, 1)
        recorder.feed(1f, 100_000)
        assertEquals(0, recorder.snapshot().size)
    }
}
