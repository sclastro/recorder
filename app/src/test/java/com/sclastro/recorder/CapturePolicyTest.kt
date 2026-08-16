package com.sclastro.recorder

import com.sclastro.recorder.audio.RecorderEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split decision runs once per 20ms chunk on the audio thread, and getting
 * it wrong means either a file that never ends or a stream of empty ones.
 */
class CapturePolicyTest {

    private val rate = 44_100

    @Test
    fun `off by default`() {
        val policy = RecorderEngine.Policy()
        assertFalse(policy.splits)
        assertFalse(policy.shouldSplit(rate * 3600L, rate, 4L * 1024 * 1024 * 1024))
    }

    @Test
    fun `splits once the minutes are up`() {
        val policy = RecorderEngine.Policy(splitMinutes = 15)
        assertFalse(policy.shouldSplit(14 * 60L * rate, rate, 0))
        assertTrue(policy.shouldSplit(15 * 60L * rate, rate, 0))
    }

    @Test
    fun `splits once the file is big enough`() {
        val policy = RecorderEngine.Policy(splitMegabytes = 100)
        assertFalse(policy.shouldSplit(rate.toLong(), rate, 99L * 1024 * 1024))
        assertTrue(policy.shouldSplit(rate.toLong(), rate, 100L * 1024 * 1024))
    }

    @Test
    fun `whichever limit comes first wins`() {
        val policy = RecorderEngine.Policy(splitMinutes = 60, splitMegabytes = 50)
        // Nowhere near an hour, but already over the size limit.
        assertTrue(policy.shouldSplit(60L * rate, rate, 50L * 1024 * 1024))
    }

    @Test
    fun `an empty segment never splits`() {
        val policy = RecorderEngine.Policy(splitMinutes = 15, splitMegabytes = 1)
        assertFalse(policy.shouldSplit(0, rate, 900L * 1024 * 1024))
    }

    @Test
    fun `an unusable sample rate never splits`() {
        val policy = RecorderEngine.Policy(splitMinutes = 15)
        assertFalse(policy.shouldSplit(1_000_000, 0, 0))
    }

    @Test
    fun `vox is off unless asked for`() {
        assertFalse(RecorderEngine.Policy().voxEnabled)
        assertTrue(RecorderEngine.Policy(voxEnabled = true).voxEnabled)
    }
}
