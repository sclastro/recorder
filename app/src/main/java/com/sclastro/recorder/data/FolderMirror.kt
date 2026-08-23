package com.sclastro.recorder.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies finished recordings into a folder the user picked with the system
 * file picker.
 *
 * Recordings themselves live in the app's own directory, and that is not going
 * to change: capture writes through a plain [java.io.FileDescriptor] on the
 * audio thread, trimming seeks around inside the file, and peaks live in
 * sidecars next to it — all of which want a real path, not a content URI. What
 * Android 11 took away is the ability to *see* that directory from a file
 * manager on the phone.
 *
 * So this is a mirror, not a move: the library keeps working the way it does,
 * and a copy of each recording also lands somewhere the rest of the phone can
 * reach. It costs disk space, which is why it is off unless asked for.
 */
class FolderMirror(private val context: Context) {

    /** What the settings screen shows about the chosen folder. */
    data class Info(val label: String?, val usable: Boolean) {
        val chosen: Boolean get() = label != null
    }

    /**
     * Both of these go through the content resolver, which is disk and IPC —
     * hence `suspend`. They were plain functions called straight from
     * composition, so every recomposition of the settings screen did two
     * blocking queries on the main thread.
     */
    suspend fun describe(treeUri: String?): Info = withContext(Dispatchers.IO) {
        val uri = treeUri?.takeIf { it.isNotBlank() }?.toUri() ?: return@withContext Info(null, false)
        val document = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        Info(
            label = document?.name ?: uri.lastPathSegment,
            usable = runCatching { document?.canWrite() == true }.getOrDefault(false),
        )
    }

    /** Whether the granted permission still holds; the user can revoke it. */
    suspend fun isUsable(treeUri: String?): Boolean = describe(treeUri).usable

    /**
     * Keeps the grant across reboots. Called once, when the folder is chosen.
     */
    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * Copies [file] into the tree, replacing an existing copy of the same name.
     * Returns false rather than throwing — a mirror failing must never take a
     * recording down with it.
     */
    suspend fun copy(file: File, treeUri: String?): Boolean = withContext(Dispatchers.IO) {
        val uri = treeUri?.takeIf { it.isNotBlank() }?.toUri() ?: return@withContext false
        if (!file.isFile) return@withContext false

        runCatching {
            val tree = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching false
            if (!tree.canWrite()) return@runCatching false

            // A second copy under the same name would be confusing, and SAF
            // happily makes "name (1)" without being asked.
            tree.findFile(file.name)?.delete()

            val target = tree.createFile(mimeTypeOf(file.extension), file.name)
                ?: return@runCatching false
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    private fun mimeTypeOf(extension: String) = when (extension.lowercase()) {
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/mp4"
    }

    private fun String.toUri(): Uri? = runCatching { Uri.parse(this) }.getOrNull()
}
