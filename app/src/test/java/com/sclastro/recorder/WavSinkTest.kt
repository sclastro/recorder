package com.sclastro.recorder

import com.sclastro.recorder.audio.BitDepth
import com.sclastro.recorder.audio.WavSink
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavSinkTest {

    @get:Rule val temp = TemporaryFolder()

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    @Test
    fun `writes a 44 byte PCM header with patched sizes`() {
        val file = File(temp.root, "a.wav")
        val payload = ByteArray(4000) { (it % 251).toByte() }
        WavSink(file, 44100, 1, BitDepth.PCM_16).use { sink ->
            sink.write(payload, payload.size)
            sink.finish()
        }

        val bytes = file.readBytes()
        assertEquals(44 + payload.size, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(16, readLe32(bytes, 16))
        assertEquals(WavSink.FORMAT_PCM, readLe16(bytes, 20))
        assertEquals(1, readLe16(bytes, 22))
        assertEquals(44100, readLe32(bytes, 24))
        assertEquals(44100 * 2, readLe32(bytes, 28))
        assertEquals(2, readLe16(bytes, 32))
        assertEquals(16, readLe16(bytes, 34))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(payload.size, readLe32(bytes, 40))
        assertEquals(36 + payload.size, readLe32(bytes, 4))
    }

    @Test
    fun `float wav declares IEEE format and carries a fact chunk`() {
        val file = File(temp.root, "b.wav")
        val payload = ByteArray(800)
        WavSink(file, 48000, 2, BitDepth.FLOAT_32).use { sink ->
            sink.write(payload, payload.size)
            sink.finish()
        }

        val bytes = file.readBytes()
        assertEquals(WavSink.FLOAT_HEADER_SIZE + payload.size, bytes.size)
        assertEquals(18, readLe32(bytes, 16))
        assertEquals(WavSink.FORMAT_IEEE_FLOAT, readLe16(bytes, 20))
        assertEquals(32, readLe16(bytes, 34))
        assertEquals("fact", String(bytes, 38, 4))
        // 800 bytes / (2 channels * 4 bytes) = 100 frames
        assertEquals(100, readLe32(bytes, 46))
        assertEquals("data", String(bytes, 50, 4))
        assertEquals(payload.size, readLe32(bytes, 54))
    }

    @Test
    fun `close without finish still patches the header`() {
        val file = File(temp.root, "c.wav")
        val sink = WavSink(file, 16000, 1, BitDepth.PCM_16)
        sink.write(ByteArray(320), 320)
        sink.close()
        assertEquals(320, readLe32(file.readBytes(), 40))
    }
}
