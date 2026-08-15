package com.sclastro.recorder.audio

import android.media.AudioRecord

/**
 * Asks the platform whether a capture configuration is actually usable.
 *
 * Without this the settings sheet offers every combination and the user only
 * finds out that 96 kHz or 24-bit is unavailable after tapping record and
 * getting an error. `getMinBufferSize` answers the same question the recorder
 * would hit, needs no permission, and is cheap enough to call per option — the
 * results are memoised anyway because they cannot change while the app runs.
 */
object AudioCapabilities {

    private val cache = HashMap<Int, Boolean>()

    fun supports(config: RecordingConfig): Boolean {
        val key = key(config)
        cache[key]?.let { return it }
        val supported = runCatching {
            AudioRecord.getMinBufferSize(
                config.sampleRate,
                config.channels.inMask,
                config.bitDepth.encoding,
            ) > 0
        }.getOrDefault(false)
        cache[key] = supported
        return supported
    }

    /** True when the device can do this rate at the currently chosen depth and channels. */
    fun supportsSampleRate(config: RecordingConfig, sampleRate: Int): Boolean =
        supports(config.copy(sampleRate = sampleRate))

    fun supportsBitDepth(config: RecordingConfig, depth: BitDepth): Boolean =
        depth.isSupported && supports(config.copy(bitDepth = depth))

    fun supportsChannels(config: RecordingConfig, channels: Channels): Boolean =
        supports(config.copy(channels = channels))

    private fun key(config: RecordingConfig): Int {
        var result = config.sampleRate
        result = 31 * result + config.bitDepth.ordinal
        result = 31 * result + config.channels.ordinal
        return result
    }
}
