package com.sclastro.recorder.audio

/**
 * Scales PCM samples in place, at whatever depth they arrived in.
 *
 * Fades and normalising are the two edits that have to touch the samples
 * themselves. Doing that through a decoder would hand back 16-bit audio and
 * quietly throw away the top eight bits of a 24-bit recording, so the maths
 * happens on the raw bytes instead — the same three cases [PcmMath.analyse]
 * already reads.
 */
object GainPass {

    /** Gain for the sample frame at [frame], counting from the start of the file. */
    fun interface Envelope {
        fun gainAt(frame: Long): Float
    }

    /** A single multiplier for the whole file, as normalising uses. */
    fun constant(gain: Float) = Envelope { gain }

    /**
     * Linear ramp up over [fadeInFrames] and down over [fadeOutFrames],
     * multiplied by [gain] throughout. Either fade may be zero.
     */
    fun fade(totalFrames: Long, fadeInFrames: Long, fadeOutFrames: Long, gain: Float = 1f) = Envelope { frame ->
        val rise = if (fadeInFrames > 0 && frame < fadeInFrames) {
            frame.toFloat() / fadeInFrames
        } else {
            1f
        }
        val remaining = totalFrames - frame
        val fall = if (fadeOutFrames > 0 && remaining in 0 until fadeOutFrames) {
            remaining.toFloat() / fadeOutFrames
        } else {
            1f
        }
        gain * rise * fall
    }

    /**
     * Applies [envelope] to [size] bytes of interleaved PCM. [startFrame] is
     * where this buffer sits in the file, so an envelope spanning the whole
     * recording still lines up when the data arrives in chunks.
     */
    fun apply(
        buffer: ByteArray,
        size: Int,
        depth: BitDepth,
        channels: Int,
        startFrame: Long,
        envelope: Envelope,
    ) {
        if (channels <= 0) return
        val bytesPerSample = depth.bits / 8
        if (bytesPerSample <= 0) return

        var i = 0
        var frame = startFrame
        var channel = 0
        var gain = envelope.gainAt(frame)

        while (i + bytesPerSample <= size) {
            when (depth) {
                BitDepth.PCM_16 -> {
                    val v = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                    val scaled = (v * gain).toInt().coerceIn(-32768, 32767)
                    buffer[i] = (scaled and 0xFF).toByte()
                    buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                }
                BitDepth.PCM_24 -> {
                    var v = (buffer[i].toInt() and 0xFF) or
                        ((buffer[i + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[i + 2].toInt() and 0xFF) shl 16)
                    if (v and 0x800000 != 0) v = v or -0x1000000
                    val scaled = (v * gain).toInt().coerceIn(-8388608, 8388607)
                    buffer[i] = (scaled and 0xFF).toByte()
                    buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                    buffer[i + 2] = ((scaled shr 16) and 0xFF).toByte()
                }
                BitDepth.FLOAT_32 -> {
                    val bits = (buffer[i].toInt() and 0xFF) or
                        ((buffer[i + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[i + 2].toInt() and 0xFF) shl 16) or
                        ((buffer[i + 3].toInt() and 0xFF) shl 24)
                    val f = Float.fromBits(bits)
                    // Float WAV is nominally -1..1 but is allowed to exceed it;
                    // clamping keeps a boosted file playable everywhere.
                    val scaled = if (f.isNaN()) 0f else (f * gain).coerceIn(-1f, 1f)
                    val out = scaled.toRawBits()
                    buffer[i] = (out and 0xFF).toByte()
                    buffer[i + 1] = ((out shr 8) and 0xFF).toByte()
                    buffer[i + 2] = ((out shr 16) and 0xFF).toByte()
                    buffer[i + 3] = ((out shr 24) and 0xFF).toByte()
                }
            }
            i += bytesPerSample
            // The envelope is per frame, so it only moves once all the
            // channels of this frame have been scaled by the same amount.
            if (++channel == channels) {
                channel = 0
                frame++
                gain = envelope.gainAt(frame)
            }
        }
    }
}
