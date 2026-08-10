package com.sclastro.recorder.audio

import java.io.Closeable

/** Somewhere captured PCM goes: a WAV file, or an encoder feeding a muxer. */
interface AudioSink : Closeable {
    /** Consumes [size] bytes from the front of [buffer]. */
    fun write(buffer: ByteArray, size: Int)

    /** Flushes, finalises headers/containers. Safe to call once. */
    fun finish()
}
