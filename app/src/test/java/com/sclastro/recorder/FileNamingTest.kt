package com.sclastro.recorder

import com.sclastro.recorder.data.FileNaming
import com.sclastro.recorder.data.RecordingStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class FileNamingTest {

    private val when20260810 = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US).parse("20260810 143205")!!

    @Test
    fun `expands date and time tokens`() {
        assertEquals("20260810_143205", FileNaming.expand("{date}_{time}", when20260810))
    }

    @Test
    fun `expands sequence preset and folder`() {
        val result = FileNaming.expand("{preset}_{folder}_{seq}", when20260810, sequence = 7, preset = "會議", folder = "客戶A")
        assertEquals("會議_客戶A_007", result)
    }

    @Test
    fun `blank template still yields a usable name`() {
        assertEquals("20260810_143205", FileNaming.expand("", when20260810))
    }

    @Test
    fun `unknown tokens are left visible rather than dropped`() {
        assertTrue(FileNaming.expand("{nope}", when20260810).contains("nope"))
    }

    @Test
    fun `path separators cannot escape the folder`() {
        assertEquals("a_b_c", RecordingStorage.sanitiseName("a/b\\c"))
        assertEquals("Recording", RecordingStorage.sanitiseName("   "))
        assertEquals("會議 記錄", RecordingStorage.sanitiseName("會議 記錄"))
    }

    /**
     * A folder called ".trash" or ".pending" would have been created on top of
     * the directories this app keeps its own bookkeeping in, and anything filed
     * there would have looked deleted.
     */
    @Test
    fun `a leading dot cannot collide with the app's own directories`() {
        assertEquals(RecordingStorage.TRASH.trimStart('.'), RecordingStorage.sanitiseName(RecordingStorage.TRASH))
        assertEquals(RecordingStorage.PENDING.trimStart('.'), RecordingStorage.sanitiseName(RecordingStorage.PENDING))
        assertEquals("hidden", RecordingStorage.sanitiseName(".hidden"))
        assertEquals("Recording", RecordingStorage.sanitiseName("..."))
        // A dot in the middle is ordinary and stays.
        assertEquals("Notes v1.2", RecordingStorage.sanitiseName("Notes v1.2"))
    }
}
