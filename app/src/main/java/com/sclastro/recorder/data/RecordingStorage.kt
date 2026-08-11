package com.sclastro.recorder.data

import android.content.Context
import java.io.File

/**
 * Where files live on disk.
 *
 * Everything sits under one root as a real directory tree, so a folder in the
 * app is a folder on the phone — plug into a computer and the structure is the
 * same. Recording writes into [pendingDir] first and the file only moves into
 * place once finalised, which is also what makes crash recovery possible.
 */
class RecordingStorage(context: Context) {

    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings").apply { mkdirs() }
    val pendingDir: File = File(root, PENDING).apply { mkdirs() }
    val trashDir: File = File(root, TRASH).apply { mkdirs() }

    fun folderDir(folder: String): File =
        if (folder.isBlank()) root else File(root, sanitiseName(folder))

    fun file(relPath: String): File = File(root, relPath)

    fun relPathOf(folder: String, fileName: String): String =
        if (folder.isBlank()) fileName else "${sanitiseName(folder)}/$fileName"

    /** Real subdirectories of the root, excluding the app's own bookkeeping. */
    fun listFolderDirs(): List<File> =
        root.listFiles { f -> f.isDirectory && f.name != PENDING && f.name != TRASH }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Picks a name that is not taken, appending " (2)", " (3)"… as needed. */
    fun uniqueFile(dir: File, baseName: String, ext: String): File {
        dir.mkdirs()
        var candidate = File(dir, "$baseName.$ext")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($n).$ext")
            n++
        }
        return candidate
    }

    companion object {
        const val PENDING = ".pending"
        const val TRASH = ".trash"

        private val ILLEGAL = Regex("[\\\\/:*?\"<>|]")

        /** Keeps spaces and CJK, drops anything a filesystem would object to. */
        fun sanitiseName(raw: String): String =
            raw.replace(ILLEGAL, "_")
                .filter { it.code >= 0x20 }
                .trim()
                .trimEnd('.')
                .take(120)
                .ifBlank { "Recording" }
    }
}
