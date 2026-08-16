package com.sclastro.recorder.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Just enough RIFF parsing to find the audio and describe it.
 *
 * Written by hand rather than through MediaExtractor because everything this
 * app does to a WAV — trimming, fading, normalising — wants the raw bytes at
 * their original bit depth, and a decoder would hand back 16-bit whatever the
 * file actually holds.
 */
internal object WavFile {

    data class Info(
        val dataOffset: Long,
        val dataLength: Long,
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        val formatTag: Int,
    ) {
        val blockAlign: Int get() = channels * (bits / 8)
        val frameCount: Long get() = if (blockAlign <= 0) 0 else dataLength / blockAlign

        val depth: BitDepth
            get() = when {
                formatTag == WavSink.FORMAT_IEEE_FLOAT -> BitDepth.FLOAT_32
                bits == 24 -> BitDepth.PCM_24
                else -> BitDepth.PCM_16
            }
    }

    fun isWav(file: File) = file.extension.equals("wav", ignoreCase = true)

    fun read(input: RandomAccessFile): Info? {
        if (input.length() < 44) return null
        input.seek(0)
        val riff = ByteArray(12)
        input.readFully(riff)
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null

        var formatTag = 1
        var channels = 1
        var sampleRate = 44100
        var bits = 16
        var sawFmt = false

        while (input.filePointer + 8 <= input.length()) {
            val header = ByteArray(8)
            input.readFully(header)
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val size = le32(header, 4)
            val body = input.filePointer
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(minOf(size, 16L).toInt())
                    input.readFully(fmt)
                    formatTag = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4).toInt()
                    bits = le16(fmt, 14)
                    sawFmt = true
                }
                "data" -> {
                    if (!sawFmt) return null
                    val available = input.length() - body
                    val length = if (size <= 0 || size > available) available else size
                    return Info(body, length, sampleRate, channels, bits, formatTag)
                }
            }
            input.seek(body + size + (size % 2))
        }
        return null
    }

    fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}
