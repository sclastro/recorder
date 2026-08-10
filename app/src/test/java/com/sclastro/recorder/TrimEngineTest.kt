package com.sclastro.recorder

import com.sclastro.recorder.audio.BitDepth
import com.sclastro.recorder.audio.TrimEngine
import com.sclastro.recorder.audio.WavSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrimEngineTest {

    @get:Rule val temp = TemporaryFolder()

    private val sampleRate = 44100
    private val blockAlign = 2 // mono, 16-bit

    /** Two seconds of a ramp, so trimmed content is identifiable by value. */
    private fun writeSource(): File {
        val file = File(temp.root, "source.wav")
        val frames = sampleRate * 2
        val payload = ByteArray(frames * blockAlign)
        for (i in 0 until frames) {
            val value = (i % 32767).toShort()
            payload[i * 2] = (value.toInt() and 0xFF).toByte()
            payload[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        WavSink(file, sampleRate, 1, BitDepth.PCM_16).use {
            it.write(payload, payload.size)
            it.finish()
        }
        return file
    }

    @Test
    fun `trimming wav keeps exactly the selected span`() {
        val source = writeSource()
        val destination = File(temp.root, "cut.wav")

        val outcome = TrimEngine.trim(source, destination, startMs = 500, endMs = 1500)
        assertTrue("expected success but got $outcome", outcome is TrimEngine.Outcome.Success)
        outcome as TrimEngine.Outcome.Success

        assertEquals(1000, outcome.durationMs)
        val bytes = destination.readBytes()
        val dataLength = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(sampleRate * blockAlign, dataLength)

        // First frame of the cut is the sample that was at 0.5 s in the source.
        val firstSample = ByteBuffer.wrap(bytes, 44, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals((sampleRate / 2) % 32767, firstSample)
    }

    @Test
    fun `selection beyond the end is clamped to the file`() {
        val source = writeSource()
        val destination = File(temp.root, "tail.wav")

        val outcome = TrimEngine.trim(source, destination, startMs = 1500, endMs = 9000)
        assertTrue(outcome is TrimEngine.Outcome.Success)
        assertEquals(500, (outcome as TrimEngine.Outcome.Success).durationMs)
    }

    @Test
    fun `an empty selection is rejected without leaving a file behind`() {
        val source = writeSource()
        val destination = File(temp.root, "empty.wav")

        val outcome = TrimEngine.trim(source, destination, startMs = 800, endMs = 800)
        assertTrue(outcome is TrimEngine.Outcome.Failure)
        assertTrue(!destination.exists())
    }
}
