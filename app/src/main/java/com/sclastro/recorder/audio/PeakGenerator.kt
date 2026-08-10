package com.sclastro.recorder.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Rebuilds a waveform by decoding a file. Only needed for audio this app did
 * not just record (imports, trimmed copies made elsewhere).
 */
object PeakGenerator {

    fun generate(file: File): ByteArray? = runCatching { decode(file) }.getOrNull()

    private fun decode(file: File): ByteArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            return ByteArray(0)
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val recorder = Peaks.Recorder(sampleRate, BitDepth.PCM_16, channels)
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        recorder.feed(peakOf(buffer), info.size)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return recorder.snapshot()
    }

    private fun peakOf(buffer: ByteBuffer): Float {
        var peak = 0f
        while (buffer.remaining() >= 2) {
            val lo = buffer.get().toInt() and 0xFF
            val hi = buffer.get().toInt()
            val v = ((hi shl 8) or lo).toShort().toInt() / 32768f
            peak = max(peak, abs(v))
        }
        return peak
    }

    private const val TIMEOUT_US = 10_000L
}
