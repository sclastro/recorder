package com.sclastro.recorder.audio

import java.io.File

/**
 * A recording's waveform, stored as one byte (0..255) per [BUCKET_MS] of audio
 * in a sidecar file. Written for free while recording, so opening a two-hour
 * file still draws instantly.
 */
object Peaks {
    const val BUCKET_MS = 50

    fun sidecarFor(audio: File): File = File(audio.parentFile, audio.name + ".peaks")

    fun save(audio: File, peaks: ByteArray) {
        runCatching { sidecarFor(audio).writeBytes(peaks) }
    }

    fun load(audio: File): ByteArray? =
        sidecarFor(audio).takeIf { it.isFile && it.length() > 0 }?.let {
            runCatching { it.readBytes() }.getOrNull()
        }

    fun delete(audio: File) {
        runCatching { sidecarFor(audio).delete() }
    }

    /** Down-samples to [target] columns for drawing, as 0f..1f. */
    fun resample(peaks: ByteArray, target: Int): FloatArray {
        if (target <= 0) return FloatArray(0)
        if (peaks.isEmpty()) return FloatArray(target)
        val out = FloatArray(target)
        for (i in 0 until target) {
            val from = (i.toLong() * peaks.size / target).toInt()
            val to = (((i + 1).toLong() * peaks.size / target).toInt()).coerceAtLeast(from + 1)
            var max = 0
            for (j in from until minOf(to, peaks.size)) {
                val v = peaks[j].toInt() and 0xFF
                if (v > max) max = v
            }
            out[i] = max / 255f
        }
        return out
    }

    /** Accumulates capture buffers into fixed-duration buckets. */
    class Recorder(private val sampleRate: Int, private val depth: BitDepth, channels: Int) {
        private val bytesPerBucket = (sampleRate.toLong() * BUCKET_MS / 1000).toInt() *
            depth.bytes * channels
        private val out = ArrayList<Byte>(4096)
        private var bytesInBucket = 0
        private var bucketPeak = 0f

        fun feed(peak: Float, byteCount: Int) {
            if (peak > bucketPeak) bucketPeak = peak
            bytesInBucket += byteCount
            while (bytesInBucket >= bytesPerBucket && bytesPerBucket > 0) {
                out.add(((bucketPeak.coerceIn(0f, 1f)) * 255f).toInt().toByte())
                bytesInBucket -= bytesPerBucket
                bucketPeak = 0f
            }
        }

        fun snapshot(): ByteArray = out.toByteArray()
    }
}
