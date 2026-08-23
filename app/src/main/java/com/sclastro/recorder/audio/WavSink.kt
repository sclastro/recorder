package com.sclastro.recorder.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes PCM straight to a RIFF/WAVE file. Sizes are placeholders while
 * recording and patched in [finish], so a file left behind by a crash is still
 * repairable from its on-disk length.
 *
 * RIFF stores those sizes in 32 bits, so a WAV cannot describe more than 4 GB
 * of itself — see [MAX_DATA_BYTES]. This app's own reader falls back to the
 * real file length, but anything else would see a wrapped, wrong number, so
 * capture splits before it gets there rather than writing a file that only
 * works here.
 */
class WavSink(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val depth: BitDepth,
) : AudioSink {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var finished = false
    private val headerSize = if (depth.isFloat) FLOAT_HEADER_SIZE else PCM_HEADER_SIZE

    init {
        raf.setLength(0)
        raf.write(buildHeader(0))
    }

    override fun write(buffer: ByteArray, size: Int) {
        raf.write(buffer, 0, size)
        dataBytes += size
    }

    override fun finish() {
        if (finished) return
        finished = true
        raf.seek(0)
        raf.write(buildHeader(dataBytes))
        raf.fd.sync()
    }

    override fun close() {
        try {
            finish()
        } finally {
            raf.close()
        }
    }

    private fun buildHeader(dataLen: Long): ByteArray {
        val blockAlign = channels * depth.bytes
        val byteRate = sampleRate * blockAlign
        val out = java.io.ByteArrayOutputStream(headerSize)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Long) {
            out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt())
            out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt())
        }
        fun i16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }

        ascii("RIFF")
        i32(headerSize - 8 + dataLen)
        ascii("WAVE")

        ascii("fmt ")
        if (depth.isFloat) {
            i32(18)
            i16(FORMAT_IEEE_FLOAT)
        } else {
            i32(16)
            i16(FORMAT_PCM)
        }
        i16(channels)
        i32(sampleRate.toLong())
        i32(byteRate.toLong())
        i16(blockAlign)
        i16(depth.bits)
        if (depth.isFloat) {
            i16(0) // cbSize
            ascii("fact")
            i32(4)
            i32(if (blockAlign == 0) 0 else dataLen / blockAlign)
        }

        ascii("data")
        i32(dataLen)
        return out.toByteArray()
    }

    companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_IEEE_FLOAT = 3
        const val PCM_HEADER_SIZE = 44
        const val FLOAT_HEADER_SIZE = 58

        /**
         * The most audio one WAV can honestly describe. The RIFF and data
         * chunk sizes are unsigned 32-bit, and the header also has to fit, so
         * this leaves a margin under 4 GiB rather than sitting exactly on it.
         *
         * This is reachable in ordinary use, which is why capture guards it:
         * about 2 hours at 96 kHz / 24-bit / stereo, 4 hours at 48 kHz, and
         * still only 13½ hours at 44.1 kHz / 16-bit / mono — against a
         * recording service that holds a wake lock for twelve.
         */
        const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 1024
    }
}
