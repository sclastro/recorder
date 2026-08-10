package com.sclastro.recorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * Feeds 16-bit PCM into a hardware/software encoder and muxes the result.
 * Handles both AAC-in-MP4 and Opus-in-OGG; the only difference is the mime type
 * and the muxer's output format.
 */
class EncodedSink(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
    bitrateKbps: Int,
    private val container: AudioContainer,
) : AudioSink {

    private val mime = when (container) {
        AudioContainer.OGG -> MediaFormat.MIMETYPE_AUDIO_OPUS
        else -> MediaFormat.MIMETYPE_AUDIO_AAC
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime).apply {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
            }
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }

    private val muxer: MediaMuxer = run {
        val format = if (container == AudioContainer.OGG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
        } else {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
        MediaMuxer(file.absolutePath, format)
    }

    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalFramesFed = 0L
    private var finished = false
    private var closed = false

    private val bytesPerFrame = 2 * channels

    override fun write(buffer: ByteArray, size: Int) {
        var offset = 0
        while (offset < size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input: ByteBuffer = requireNotNull(codec.getInputBuffer(index))
                input.clear()
                val chunk = minOf(input.remaining(), size - offset)
                input.put(buffer, offset, chunk)
                val ptsUs = totalFramesFed * 1_000_000L / sampleRate
                codec.queueInputBuffer(index, 0, chunk, ptsUs, 0)
                totalFramesFed += chunk / bytesPerFrame
                offset += chunk
            }
            drain(false)
        }
    }

    override fun finish() {
        if (finished) return
        finished = true
        // Signal end of stream, then let the encoder flush what it is holding.
        var queued = false
        while (!queued) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val ptsUs = totalFramesFed * 1_000_000L / sampleRate
                codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                drain(false)
            }
        }
        drain(true)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { finish() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(untilEos: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (untilEos) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "encoder changed format after start" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && bufferInfo.size > 0 && muxerStarted &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, output, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT = 32 * 1024
    }
}
