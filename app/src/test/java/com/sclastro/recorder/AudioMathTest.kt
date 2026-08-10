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
