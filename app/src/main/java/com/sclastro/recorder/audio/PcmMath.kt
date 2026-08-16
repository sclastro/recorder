package com.sclastro.recorder.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Peak and RMS extraction straight off the raw capture buffer. */
object PcmMath {

    /** Returns peak (0..1) and RMS (0..1) for [size] bytes of [buffer]. */
    fun analyse(buffer: ByteArray, size: Int, depth: BitDepth): Pair<Float, Float> {
        var peak = 0f
        var sumSquares = 0.0
        var count = 0
        when (depth) {
            BitDepth.PCM_16 -> {
                var i = 0
                while (i + 1 < size) {
                    val v = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                    val f = v / 32768f
                    peak = max(peak, abs(f))
                    sumSquares += (f * f).toDouble()
                    count++
                    i += 2
                }
            }
            BitDepth.PCM_24 -> {
                var i = 0
                while (i + 2 < size) {
                    var v = (buffer[i].toInt() and 0xFF) or
                        ((buffer[i + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[i + 2].toInt() and 0xFF) shl 16)
                    if (v and 0x800000 != 0) v = v or -0x1000000
                    val f = v / 8388608f
                    peak = max(peak, abs(f))
                    sumSquares += (f * f).toDouble()
                    count++
                    i += 3
                }
            }
            BitDepth.FLOAT_32 -> {
                var i = 0
                while (i + 3 < size) {
                    val bits = (buffer[i].toInt() and 0xFF) or
                        ((buffer[i + 1].toInt() and 0xFF) shl 8) or
                        ((buffer[i + 2].toInt() and 0xFF) shl 16) or
                        ((buffer[i + 3].toInt() and 0xFF) shl 24)
                    val f = Float.fromBits(bits)
                    if (!f.isNaN()) {
                        peak = max(peak, abs(f))
                        sumSquares += (f * f).toDouble()
                    }
                    count++
                    i += 4
                }
            }
        }
        val rms = if (count == 0) 0f else sqrt(sumSquares / count).toFloat()
        return peak.coerceIn(0f, 1f) to rms.coerceIn(0f, 1f)
    }

    /** Linear amplitude to dBFS, floored at [floor] so the meter has a bottom. */
    fun toDb(amplitude: Float, floor: Float = -60f): Float {
        if (amplitude <= 0.0000001f) return floor
        return (20f * log10(amplitude)).coerceIn(floor, 0f)
    }

    /** Maps dBFS onto 0..1 for meters and waveforms. */
    fun dbToFraction(db: Float, floor: Float = -60f): Float =
        ((db - floor) / -floor).coerceIn(0f, 1f)

    /**
     * Narrows [size] bytes of PCM at [depth] down to little-endian 16-bit,
     * which is the only thing the encoders accept. A 16-bit input is copied
     * unchanged rather than round-tripped.
     */
    fun toPcm16(buffer: ByteArray, size: Int, depth: BitDepth): ByteArray = when (depth) {
        BitDepth.PCM_16 -> buffer.copyOf(size)

        BitDepth.PCM_24 -> {
            val samples = size / 3
            val out = ByteArray(samples * 2)
            var i = 0
            var o = 0
            while (i + 2 < size) {
                // Keep the top two bytes; the third is the detail being dropped.
                out[o] = buffer[i + 1]
                out[o + 1] = buffer[i + 2]
                i += 3
                o += 2
            }
            out
        }

        BitDepth.FLOAT_32 -> {
            val samples = size / 4
            val out = ByteArray(samples * 2)
            var i = 0
            var o = 0
            while (i + 3 < size) {
                val bits = (buffer[i].toInt() and 0xFF) or
                    ((buffer[i + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[i + 2].toInt() and 0xFF) shl 16) or
                    ((buffer[i + 3].toInt() and 0xFF) shl 24)
                val f = Float.fromBits(bits)
                val v = if (f.isNaN()) 0 else (f.coerceIn(-1f, 1f) * 32767f).toInt()
                out[o] = (v and 0xFF).toByte()
                out[o + 1] = ((v shr 8) and 0xFF).toByte()
                i += 4
                o += 2
            }
            out
        }
    }
}
