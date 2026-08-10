package com.sclastro.recorder.data

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile

/** Reads duration and stream layout from a file the app did not just record. */
object MediaProbe {

    data class Info(
        val durationMs: Long = 0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bitDepth: Int = 0,
    )

    fun probe(file: File): Info {
        if (file.extension.equals("wav", ignoreCase = true)) {
            probeWav(file)?.let { return it }
        }
        val fromExtractor = runCatching { probeWithExtractor(file) }.getOrNull()
        val duration = fromExtractor?.durationMs?.takeIf { it > 0 } ?: runCatching {
            // MediaMetadataRetriever only became AutoCloseable in API 29.
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
        return (fromExtractor ?: Info()).copy(durationMs = duration)
    }

    private fun probeWithExtractor(file: File): Info? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(index)
            return Info(
                durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION) / 1000
                } else {
                    0
                },
                sampleRate = format.getIntOrZero(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getIntOrZero(MediaFormat.KEY_CHANNEL_COUNT),
                bitDepth = 0,
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** WAV needs no decoder — the header says everything. */
    private fun probeWav(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 44) return null
            val riff = ByteArray(12)
            input.readFully(riff)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF") return null

            var channels = 0
            var sampleRate = 0
            var bits = 0
            while (input.filePointer + 8 <= input.length()) {
                val header = ByteArray(8)
                input.readFully(header)
                val id = String(header, 0, 4, Charsets.US_ASCII)
                val size = le32(header, 4)
                val body = input.filePointer
                if (id == "fmt ") {
                    val fmt = ByteArray(minOf(size, 16L).toInt())
                    input.readFully(fmt)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4).toInt()
                    bits = le16(fmt, 14)
                } else if (id == "data") {
                    val blockAlign = channels * (bits / 8)
                    val available = input.length() - body
                    val length = if (size <= 0 || size > available) available else size
                    val duration = if (blockAlign > 0 && sampleRate > 0) {
                        length * 1000 / (sampleRate.toLong() * blockAlign)
                    } else {
                        0
                    }
                    return Info(duration, sampleRate, channels, bits)
                }
                input.seek(body + size + (size % 2))
            }
            null
        }
    }.getOrNull()

    private fun MediaFormat.getIntOrZero(key: String) = if (containsKey(key)) getInteger(key) else 0

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}
