package com.sclastro.recorder.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Edits that change the samples rather than just choosing which of them to
 * keep: fades and normalising.
 *
 * These only run on WAV. Everything else this app does to a recording is
 * lossless — trimming and splitting copy bytes or packets — and applying a
 * fade to an AAC file would mean decode, process, re-encode, which loses
 * quality every time it is done. WAV is the format to reach for when the
 * recording is going to be worked on; the UI says so rather than silently
 * degrading an M4A.
 */
object EditEngine {

    sealed interface Outcome {
        data class Success(val file: File, val durationMs: Long) : Outcome
        data class Failure(val message: String) : Outcome
    }

    /** True when [file] can take a fade or a normalise. */
    fun supports(file: File) = WavFile.isWav(file)

    private const val UNSUPPORTED = "Fades and normalising need a WAV recording — " +
        "compressed audio would have to be re-encoded and would lose quality"

    /**
     * Ramps the first [fadeInMs] up from silence and the last [fadeOutMs] back
     * down. A fade longer than the recording is clamped rather than refused.
     */
    fun fade(source: File, destination: File, fadeInMs: Long, fadeOutMs: Long): Outcome {
        if (!supports(source)) return Outcome.Failure(UNSUPPORTED)
        if (fadeInMs <= 0 && fadeOutMs <= 0) return Outcome.Failure("Nothing to fade")
        return withWav(source, destination) { info ->
            val total = info.frameCount
            val inFrames = (fadeInMs * info.sampleRate / 1000).coerceIn(0, total)
            val outFrames = (fadeOutMs * info.sampleRate / 1000).coerceIn(0, total - inFrames)
            GainPass.fade(total, inFrames, outFrames)
        }
    }

    /**
     * Lifts the whole file so its loudest sample sits at [targetPeak].
     *
     * Two passes over the audio: the first only measures, because the gain
     * cannot be known until the loudest moment has been seen, and holding a
     * long recording in memory to avoid the second read is not worth it.
     */
    fun normalize(source: File, destination: File, targetPeak: Float = 0.97f): Outcome {
        if (!supports(source)) return Outcome.Failure(UNSUPPORTED)
        val peak = peakOf(source) ?: return Outcome.Failure("Could not read the WAV structure")
        if (peak <= 0f) return Outcome.Failure("This recording is silent")
        val gain = targetPeak / peak
        if (gain <= 1.001f) return Outcome.Failure("Already as loud as it can go without clipping")
        return withWav(source, destination) { GainPass.constant(gain) }
    }

    private fun peakOf(source: File): Float? = runCatching {
        RandomAccessFile(source, "r").use { input ->
            val info = WavFile.read(input) ?: return@use null
            input.seek(info.dataOffset)
            val buffer = ByteArray(COPY_BUFFER)
            var remaining = info.dataLength
            var peak = 0f
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                val (chunkPeak, _) = PcmMath.analyse(buffer, read, info.depth)
                if (chunkPeak > peak) peak = chunkPeak
                remaining -= read
            }
            peak
        }
    }.getOrNull()

    /** Streams the source through [envelopeFor] into a new WAV of the same shape. */
    private fun withWav(
        source: File,
        destination: File,
        envelopeFor: (WavFile.Info) -> GainPass.Envelope,
    ): Outcome {
        destination.parentFile?.mkdirs()
        return try {
            RandomAccessFile(source, "r").use { input ->
                val info = WavFile.read(input)
                    ?: return Outcome.Failure("Could not read the WAV structure")
                if (info.blockAlign <= 0) return Outcome.Failure("Unexpected WAV format")
                val envelope = envelopeFor(info)

                WavSink(destination, info.sampleRate, info.channels, info.depth).use { sink ->
                    input.seek(info.dataOffset)
                    // A whole number of frames per chunk, so a frame is never
                    // split across two calls and given two different gains.
                    val chunk = (COPY_BUFFER / info.blockAlign).coerceAtLeast(1) * info.blockAlign
                    val buffer = ByteArray(chunk)
                    var remaining = info.dataLength
                    var frame = 0L
                    while (remaining > 0) {
                        val want = minOf(remaining, chunk.toLong()).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read <= 0) break
                        GainPass.apply(buffer, read, info.depth, info.channels, frame, envelope)
                        sink.write(buffer, read)
                        frame += read / info.blockAlign
                        remaining -= read
                    }
                    sink.finish()
                }
                PeakGenerator.generate(destination)?.let { Peaks.save(destination, it) }
                Outcome.Success(destination, info.frameCount * 1000 / info.sampleRate)
            }
        } catch (e: Exception) {
            destination.delete()
            Outcome.Failure(e.message ?: "Edit failed")
        }
    }

    private const val COPY_BUFFER = 256 * 1024
}
