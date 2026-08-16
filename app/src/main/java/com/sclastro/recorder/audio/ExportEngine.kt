package com.sclastro.recorder.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Re-encodes a recording smaller so it can be sent somewhere.
 *
 * Sharing normally hands over the original, which is right — it is the best
 * copy. But an hour of 48 kHz 24-bit WAV is about 800 MB, and no messaging app
 * will take that. This is the one operation in the app that deliberately loses
 * quality, so it always writes a separate file and never touches the original.
 */
object ExportEngine {

    sealed interface Outcome {
        data class Success(val file: File, val durationMs: Long) : Outcome
        data class Failure(val message: String) : Outcome
    }

    /** Offered bitrates, in kbps. 64 is fine for speech, 192 for music. */
    val BITRATES = listOf(32, 64, 96, 128, 192)

    fun export(source: File, destination: File, bitrateKbps: Int): Outcome {
        destination.parentFile?.mkdirs()
        return try {
            val outcome = if (WavFile.isWav(source)) {
                encodeWav(source, destination, bitrateKbps)
            } else {
                transcode(source, destination, bitrateKbps)
            }
            if (outcome is Outcome.Failure) destination.delete()
            outcome
        } catch (e: Exception) {
            destination.delete()
            Outcome.Failure(e.message ?: "Export failed")
        }
    }

    /** WAV is already PCM, so there is nothing to decode first. */
    private fun encodeWav(source: File, destination: File, bitrateKbps: Int): Outcome {
        RandomAccessFile(source, "r").use { input ->
            val info = WavFile.read(input) ?: return Outcome.Failure("Could not read the WAV structure")
            if (info.blockAlign <= 0) return Outcome.Failure("Unexpected WAV format")

            EncodedSink(destination, info.sampleRate, info.channels, bitrateKbps, AudioContainer.M4A).use { sink ->
                input.seek(info.dataOffset)
                val buffer = ByteArray(COPY_BUFFER)
                var remaining = info.dataLength
                while (remaining > 0) {
                    val want = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    // The encoder only takes 16-bit, so a deeper source is
                    // narrowed here rather than pretending otherwise.
                    val pcm16 = PcmMath.toPcm16(buffer, read, info.depth)
                    if (pcm16.isNotEmpty()) sink.write(pcm16, pcm16.size)
                    remaining -= read
                }
                sink.finish()
            }
            return Outcome.Success(destination, info.frameCount * 1000 / info.sampleRate)
        }
    }

    /** Compressed in, compressed out: decode to PCM and encode again. */
    private fun transcode(source: File, destination: File, bitrateKbps: Int): Outcome {
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
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val sink = EncodedSink(destination, sampleRate, channels, bitrateKbps, AudioContainer.M4A)
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var lastPtsUs = 0L

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val buffer = decoder.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        sink.write(bytes, bytes.size)
                        lastPtsUs = info.presentationTimeUs
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
            sink.finish()
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
            runCatching { sink.close() }
        }
        return Outcome.Success(destination, lastPtsUs / 1000)
    }

    private const val TIMEOUT_US = 10_000L
    private const val COPY_BUFFER = 256 * 1024
}
