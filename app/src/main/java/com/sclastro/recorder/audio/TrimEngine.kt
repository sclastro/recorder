package com.sclastro.recorder.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Cuts a section out of a recording without re-encoding it.
 *
 * WAV is exact to the sample — it is just a byte-range copy. Compressed files
 * are copied packet by packet through a muxer, so the cut lands on the nearest
 * frame boundary (about 20-40 ms) but the audio itself is bit-identical.
 */
object TrimEngine {

    sealed interface Outcome {
        data class Success(val file: File, val durationMs: Long) : Outcome
        data class Failure(val message: String) : Outcome
    }

    fun trim(source: File, destination: File, startMs: Long, endMs: Long): Outcome {
        if (endMs <= startMs) return Outcome.Failure("Selection is too short")
        destination.parentFile?.mkdirs()
        return try {
            val outcome = if (source.extension.equals("wav", ignoreCase = true)) {
                trimWav(source, destination, startMs, endMs)
            } else {
                trimPackets(source, destination, startMs, endMs)
            }
            if (outcome is Outcome.Success) {
                PeakGenerator.generate(destination)?.let { Peaks.save(destination, it) }
            } else {
                destination.delete()
            }
            outcome
        } catch (e: Exception) {
            destination.delete()
            Outcome.Failure(e.message ?: "Trim failed")
        }
    }

    // ---- WAV: sample-accurate byte copy -------------------------------------

    private data class WavInfo(
        val dataOffset: Long,
        val dataLength: Long,
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        val formatTag: Int,
    ) {
        val blockAlign: Int get() = channels * (bits / 8)
    }

    private fun trimWav(source: File, destination: File, startMs: Long, endMs: Long): Outcome {
        RandomAccessFile(source, "r").use { input ->
            val info = readWavInfo(input) ?: return Outcome.Failure("Could not read the WAV structure")
            if (info.blockAlign <= 0) return Outcome.Failure("Unexpected WAV format")

            // Work in frames, not bytes-per-millisecond: at 44.1 kHz the latter
            // is not a whole number and the cut would drift.
            val totalFrames = info.dataLength / info.blockAlign
            val fromFrame = (startMs * info.sampleRate / 1000).coerceIn(0, totalFrames)
            val toFrame = (endMs * info.sampleRate / 1000).coerceIn(0, totalFrames)
            val frames = toFrame - fromFrame
            if (frames <= 0) return Outcome.Failure("Selection is too short")
            val from = fromFrame * info.blockAlign
            val length = frames * info.blockAlign

            val depth = when {
                info.formatTag == WavSink.FORMAT_IEEE_FLOAT -> BitDepth.FLOAT_32
                info.bits == 24 -> BitDepth.PCM_24
                else -> BitDepth.PCM_16
            }
            WavSink(destination, info.sampleRate, info.channels, depth).use { sink ->
                input.seek(info.dataOffset + from)
                val buffer = ByteArray(COPY_BUFFER)
                var remaining = length
                while (remaining > 0) {
                    val want = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    sink.write(buffer, read)
                    remaining -= read
                }
                sink.finish()
            }
            val durationMs = frames * 1000 / info.sampleRate
            return Outcome.Success(destination, durationMs)
        }
    }

    private fun readWavInfo(input: RandomAccessFile): WavInfo? {
        if (input.length() < 44) return null
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
                    return WavInfo(body, length, sampleRate, channels, bits, formatTag)
                }
            }
            input.seek(body + size + (size % 2))
        }
        return null
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    // ---- Compressed: packet copy through a muxer ----------------------------

    private fun trimPackets(source: File, destination: File, startMs: Long, endMs: Long): Outcome {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            return Outcome.Failure("No audio track in this file")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()

        val outputFormat = when {
            mime.contains("opus", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

        val muxer = MediaMuxer(destination.absolutePath, outputFormat)
        val outTrack = muxer.addTrack(format)
        muxer.start()

        val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(16 * 1024)
        } else {
            256 * 1024
        }
        val buffer = ByteBuffer.allocate(maxInput)
        val info = MediaCodec.BufferInfo()
        val startUs = startMs * 1000
        val endUs = endMs * 1000
        var firstPtsUs = -1L
        var lastPtsUs = 0L
        var wrote = false

        try {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                if (pts >= startUs) {
                    if (firstPtsUs < 0) firstPtsUs = pts
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = pts - firstPtsUs
                    // Extractor sample flags and codec buffer flags are different
                    // namespaces; only the key-frame bit carries over.
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(outTrack, buffer, info)
                    lastPtsUs = pts - firstPtsUs
                    wrote = true
                }
                if (!extractor.advance()) break
            }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            extractor.release()
        }

        return if (wrote) {
            Outcome.Success(destination, lastPtsUs / 1000)
        } else {
            Outcome.Failure("No audio in the selected range")
        }
    }

    private const val COPY_BUFFER = 256 * 1024
}
