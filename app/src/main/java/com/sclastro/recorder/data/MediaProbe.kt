package com.sclastro.recorder.data

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.sclastro.recorder.audio.WavFile
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

    /**
     * WAV needs no decoder — the header says everything.
     *
     * Shares [WavFile] with the trim and edit engines rather than carrying its
     * own copy of the RIFF parser, so a fix to one is a fix to all of them.
     */
    private fun probeWav(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val info = WavFile.read(input) ?: return@use null
            Info(
                durationMs = if (info.sampleRate > 0) info.frameCount * 1000 / info.sampleRate else 0,
                sampleRate = info.sampleRate,
                channels = info.channels,
                bitDepth = info.bits,
            )
        }
    }.getOrNull()

    private fun MediaFormat.getIntOrZero(key: String) = if (containsKey(key)) getInteger(key) else 0
}
