package com.sclastro.recorder.audio

import android.media.AudioFormat
import android.media.MediaRecorder
import android.os.Build

/** Container/codec the finished file is written in. */
enum class AudioContainer(
    val ext: String,
    val label: String,
    val mimeType: String,
    val lossless: Boolean,
) {
    WAV("wav", "WAV · lossless PCM", "audio/wav", true),
    M4A("m4a", "M4A · AAC", "audio/mp4a-latm", false),
    OGG("ogg", "OGG · Opus", "audio/opus", false);

    /** Opus encoding and the OGG muxer both landed in API 29. */
    val isSupported: Boolean
        get() = this != OGG || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Compressed encoders in this app take 16-bit PCM only. */
    val fixedBitDepth: BitDepth?
        get() = if (this == WAV) null else BitDepth.PCM_16
}

enum class BitDepth(val bits: Int, val label: String, val bytes: Int, val isFloat: Boolean) {
    PCM_16(16, "16-bit", 2, false),
    PCM_24(24, "24-bit", 3, false),
    FLOAT_32(32, "32-bit float", 4, true);

    val encoding: Int
        get() = when (this) {
            PCM_16 -> AudioFormat.ENCODING_PCM_16BIT
            PCM_24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            FLOAT_32 -> AudioFormat.ENCODING_PCM_FLOAT
        }

    val isSupported: Boolean
        get() = this != PCM_24 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

enum class Channels(val count: Int, val label: String) {
    MONO(1, "Mono"),
    STEREO(2, "Stereo");

    val inMask: Int
        get() = if (this == MONO) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
}

/**
 * Which hardware path the audio comes from. [UNPROCESSED] is the one that
 * matters for music: it bypasses the AGC/noise-suppression the phone otherwise
 * applies to voice.
 */
enum class MicSource(val label: String, val hint: String) {
    MIC("Default mic", "General purpose"),
    VOICE_RECOGNITION("Voice", "Bypasses most processing — cleanest speech"),
    UNPROCESSED("Unprocessed", "No AGC or noise suppression — use this for music"),
    CAMCORDER("Directional", "Rear mic, less room noise");

    val value: Int
        get() = when (this) {
            MIC -> MediaRecorder.AudioSource.MIC
            VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            UNPROCESSED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.MIC
            }
            CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        }
}

val SAMPLE_RATES = listOf(8000, 16000, 22050, 32000, 44100, 48000, 96000)
val BITRATES_KBPS = listOf(48, 64, 96, 128, 160, 192, 256, 320)

data class RecordingConfig(
    val sampleRate: Int = 44100,
    val bitDepth: BitDepth = BitDepth.PCM_16,
    val channels: Channels = Channels.MONO,
    val container: AudioContainer = AudioContainer.M4A,
    val bitrateKbps: Int = 128,
    val source: MicSource = MicSource.MIC,
    val echoCancel: Boolean = false,
    val noiseSuppress: Boolean = false,
    val autoGain: Boolean = false,
) {
    /** Coerces the combination into something the encoder can actually accept. */
    fun normalised(): RecordingConfig {
        val container = if (container.isSupported) container else AudioContainer.M4A
        val depth = container.fixedBitDepth
            ?: bitDepth.takeIf { it.isSupported }
            ?: BitDepth.PCM_16
        return copy(container = container, bitDepth = depth)
    }

    val bytesPerFrame: Int get() = bitDepth.bytes * channels.count

    /** Rough bytes-per-second, for the "space left" readout. */
    fun bytesPerSecond(): Long = when (container) {
        AudioContainer.WAV -> sampleRate.toLong() * bytesPerFrame
        else -> bitrateKbps * 1000L / 8
    }

    fun summary(): String = buildString {
        append(if (sampleRate % 1000 == 0) "${sampleRate / 1000}kHz" else "%.1fkHz".format(sampleRate / 1000f))
        append(" · ")
        append(if (container == AudioContainer.WAV) bitDepth.label else "${bitrateKbps}kbps")
        append(" · ")
        append(channels.label)
    }
}

/** Ready-made combinations so the common cases are one tap away. */
enum class Preset(val label: String, val config: RecordingConfig) {
    VOICE_MEMO(
        "Voice memo",
        RecordingConfig(16000, BitDepth.PCM_16, Channels.MONO, AudioContainer.M4A, 64, MicSource.VOICE_RECOGNITION),
    ),
    MEETING(
        "Meeting",
        RecordingConfig(44100, BitDepth.PCM_16, Channels.MONO, AudioContainer.M4A, 128, MicSource.MIC, noiseSuppress = true),
    ),
    INTERVIEW(
        "Interview",
        RecordingConfig(48000, BitDepth.PCM_24, Channels.STEREO, AudioContainer.WAV, 0, MicSource.MIC),
    ),
    MUSIC(
        "Music",
        RecordingConfig(48000, BitDepth.PCM_24, Channels.STEREO, AudioContainer.WAV, 0, MicSource.UNPROCESSED),
    ),
    CUSTOM("Custom", RecordingConfig()),
}
