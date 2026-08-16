package com.sclastro.recorder

import com.sclastro.recorder.audio.BitDepth
import com.sclastro.recorder.audio.GainPass
import com.sclastro.recorder.audio.PcmMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fades and normalising rewrite the samples, so the arithmetic has to be right
 * at every bit depth. Getting the clamp wrong turns a boost into a wrap-around,
 * which sounds like a burst of noise rather than a loud recording.
 */
class GainPassTest {

    private fun pcm16(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readPcm16(buffer: ByteArray, index: Int): Int =
        ((buffer[index * 2 + 1].toInt() shl 8) or (buffer[index * 2].toInt() and 0xFF)).toShort().toInt()

    @Test
    fun `constant gain scales every sample`() {
        val buffer = pcm16(1000, -2000, 3000)
        GainPass.apply(buffer, buffer.size, BitDepth.PCM_16, 1, 0, GainPass.constant(2f))
        assertEquals(2000, readPcm16(buffer, 0))
        assertEquals(-4000, readPcm16(buffer, 1))
        assertEquals(6000, readPcm16(buffer, 2))
    }

    @Test
    fun `a boost clamps instead of wrapping`() {
        val buffer = pcm16(30000, -30000)
        GainPass.apply(buffer, buffer.size, BitDepth.PCM_16, 1, 0, GainPass.constant(4f))
        assertEquals(32767, readPcm16(buffer, 0))
        assertEquals(-32768, readPcm16(buffer, 1))
    }

    @Test
    fun `a fade in starts at silence and reaches full`() {
        // Ten frames, mono, all at the same level, faded in over all ten.
        val buffer = pcm16(*IntArray(10) { 10000 }.toTypedArray().toIntArray())
        GainPass.apply(buffer, buffer.size, BitDepth.PCM_16, 1, 0, GainPass.fade(10, 10, 0))
        assertEquals(0, readPcm16(buffer, 0))
        assertEquals(5000, readPcm16(buffer, 5))
        assertEquals(9000, readPcm16(buffer, 9))
    }

    @Test
    fun `a fade out ends at silence`() {
        val buffer = pcm16(*IntArray(10) { 10000 }.toTypedArray().toIntArray())
        GainPass.apply(buffer, buffer.size, BitDepth.PCM_16, 1, 0, GainPass.fade(10, 0, 10))
        assertEquals(10000, readPcm16(buffer, 0))
        assertEquals(5000, readPcm16(buffer, 5))
        assertEquals(1000, readPcm16(buffer, 9))
    }

    @Test
    fun `both channels of a frame get the same gain`() {
        // Stereo: two samples per frame, so a per-frame envelope must not
        // advance between the left and right of the same instant.
        val buffer = pcm16(10000, 10000, 10000, 10000)
        GainPass.apply(buffer, buffer.size, BitDepth.PCM_16, 2, 0, GainPass.fade(2, 2, 0))
        assertEquals(readPcm16(buffer, 0), readPcm16(buffer, 1))
        assertEquals(readPcm16(buffer, 2), readPcm16(buffer, 3))
        assertTrue(readPcm16(buffer, 2) > readPcm16(buffer, 0))
    }

    @Test
    fun `startFrame keeps a chunked fade lined up`() {
        val whole = pcm16(*IntArray(8) { 10000 }.toTypedArray().toIntArray())
        GainPass.apply(whole, whole.size, BitDepth.PCM_16, 1, 0, GainPass.fade(8, 8, 0))

        // The same audio, faded in two halves, has to come out identical.
        val first = pcm16(*IntArray(4) { 10000 }.toTypedArray().toIntArray())
        val second = pcm16(*IntArray(4) { 10000 }.toTypedArray().toIntArray())
        GainPass.apply(first, first.size, BitDepth.PCM_16, 1, 0, GainPass.fade(8, 8, 0))
        GainPass.apply(second, second.size, BitDepth.PCM_16, 1, 4, GainPass.fade(8, 8, 0))

        assertTrue(whole.copyOfRange(0, 8).contentEquals(first))
        assertTrue(whole.copyOfRange(8, 16).contentEquals(second))
    }

    @Test
    fun `24-bit round trips through the scaler`() {
        // -1 in 24-bit is 0xFFFFFF; halving it should stay negative and small.
        val buffer = byteArrayOf(0x00, 0x00, 0x40) // +0x400000 = half scale
        GainPass.apply(buffer, buffer.size, BitDepth.PCM_24, 1, 0, GainPass.constant(2f))
        val (peak, _) = PcmMath.analyse(buffer, buffer.size, BitDepth.PCM_24)
        assertEquals(1f, peak, 0.01f)
    }

    @Test
    fun `float samples are clamped to the nominal range`() {
        val buffer = ByteArray(4)
        val bits = 0.8f.toRawBits()
        buffer[0] = (bits and 0xFF).toByte()
        buffer[1] = ((bits shr 8) and 0xFF).toByte()
        buffer[2] = ((bits shr 16) and 0xFF).toByte()
        buffer[3] = ((bits shr 24) and 0xFF).toByte()

        GainPass.apply(buffer, buffer.size, BitDepth.FLOAT_32, 1, 0, GainPass.constant(4f))
        val (peak, _) = PcmMath.analyse(buffer, buffer.size, BitDepth.FLOAT_32)
        assertEquals(1f, peak, 0.001f)
    }
}

/**
 * Encoders only take 16-bit, so a 24-bit or float recording has to be narrowed
 * before export. Losing the bottom bits is expected; losing the sign is not.
 */
class Pcm16NarrowingTest {

    @Test
    fun `16-bit is copied unchanged`() {
        val buffer = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        val out = PcmMath.toPcm16(buffer, buffer.size, BitDepth.PCM_16)
        assertTrue(buffer.contentEquals(out))
    }

    @Test
    fun `24-bit keeps the top two bytes`() {
        // 0x123456 -> 0x1234, little-endian on the way in and out.
        val buffer = byteArrayOf(0x56, 0x34, 0x12)
        val out = PcmMath.toPcm16(buffer, buffer.size, BitDepth.PCM_24)
        assertEquals(2, out.size)
        assertEquals(0x34.toByte(), out[0])
        assertEquals(0x12.toByte(), out[1])
    }

    @Test
    fun `a negative 24-bit sample stays negative`() {
        // 0xFF8000 is a large negative value; the high byte must survive.
        val buffer = byteArrayOf(0x00, 0x00.toByte(), 0x80.toByte())
        val out = PcmMath.toPcm16(buffer, buffer.size, BitDepth.PCM_24)
        val v = ((out[1].toInt() shl 8) or (out[0].toInt() and 0xFF)).toShort().toInt()
        assertTrue("expected a negative sample, got $v", v < 0)
    }

    @Test
    fun `float full scale maps to full scale 16-bit`() {
        val buffer = ByteArray(4)
        val bits = 1.0f.toRawBits()
        for (i in 0 until 4) buffer[i] = ((bits shr (i * 8)) and 0xFF).toByte()
        val out = PcmMath.toPcm16(buffer, buffer.size, BitDepth.FLOAT_32)
        val v = ((out[1].toInt() shl 8) or (out[0].toInt() and 0xFF)).toShort().toInt()
        assertEquals(32767, v)
    }

    @Test
    fun `float beyond full scale is clamped, not wrapped`() {
        val buffer = ByteArray(4)
        val bits = (-3.5f).toRawBits()
        for (i in 0 until 4) buffer[i] = ((bits shr (i * 8)) and 0xFF).toByte()
        val out = PcmMath.toPcm16(buffer, buffer.size, BitDepth.FLOAT_32)
        val v = ((out[1].toInt() shl 8) or (out[0].toInt() and 0xFF)).toShort().toInt()
        assertEquals(-32767, v)
    }
}
