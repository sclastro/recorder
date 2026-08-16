package com.sclastro.recorder.data

import com.sclastro.recorder.audio.PeakGenerator
import com.sclastro.recorder.audio.Peaks
import com.sclastro.recorder.audio.RecorderEngine
import com.sclastro.recorder.data.db.FolderDao
import com.sclastro.recorder.data.db.FolderEntity
import com.sclastro.recorder.data.db.RecordingDao
import com.sclastro.recorder.data.db.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** UI-facing view of a recording, with the on-disk file already resolved. */
data class Recording(
    val id: Long,
    val displayName: String,
    val folder: String,
    val file: File,
    val relPath: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val format: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val favorite: Boolean,
    val note: String,
    val bookmarks: List<Long>,
    val lastPositionMs: Long,
    val deletedAt: Long?,
) {
    val extension: String get() = file.extension
    val exists: Boolean get() = file.isFile

    /**
     * How far through this was left, for the list to draw. Zero unless it was
     * genuinely stopped part-way — the first and last few seconds count as
     * "not started" and "finished", which is the same rule playback resumes by.
     */
    val listenedFraction: Float
        get() {
            if (durationMs <= 0 || lastPositionMs <= RESUME_EDGE_MS) return 0f
            if (lastPositionMs >= durationMs - RESUME_EDGE_MS) return 0f
            return (lastPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }

    fun qualityLine(): String = buildString {
        append(format)
        append(" · ")
        append(if (sampleRate % 1000 == 0) "${sampleRate / 1000}kHz" else "%.1fkHz".format(sampleRate / 1000f))
        // Bit depth only means something for PCM; quoting it for AAC or Opus
        // describes the decoder, not the file.
        if (bitDepth > 0 && format.equals("WAV", ignoreCase = true)) {
            append(" · ")
            append("${bitDepth}bit")
        }
        append(" · ")
        append(if (channels >= 2) "Stereo" else "Mono")
    }

    private companion object {
        const val RESUME_EDGE_MS = 3_000L
    }
}

data class FolderInfo(val name: String, val count: Int, val colorIndex: Int) {
    val isRoot: Boolean get() = name.isEmpty()
    val label: String get() = if (isRoot) "Unsorted" else name
}

class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val folderDao: FolderDao,
    val storage: RecordingStorage,
) {

    fun observeRecordings(): Flow<List<Recording>> =
        recordingDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeTrash(): Flow<List<Recording>> =
        recordingDao.observeTrash().map { rows -> rows.map { it.toDomain() } }

    fun observeTrashCount(): Flow<Int> = recordingDao.observeTrashCount()

    fun observeFolders(): Flow<List<FolderInfo>> =
        combine(folderDao.observeAll(), recordingDao.observeAll()) { folders, recordings ->
            val counts = recordings.groupingBy { it.folder }.eachCount()
            val declared = folders.map { FolderInfo(it.name, counts[it.name] ?: 0, it.colorIndex) }
            val undeclared = counts.keys
                .filter { name -> name.isNotEmpty() && folders.none { it.name == name } }
                .map { FolderInfo(it, counts[it] ?: 0, 0) }
            buildList {
                add(FolderInfo("", counts[""] ?: 0, 0))
                addAll((declared + undeclared).sortedBy { it.name })
            }
        }

    suspend fun byId(id: Long): Recording? = recordingDao.byId(id)?.toDomain()

    // ---- Creation -----------------------------------------------------------

    /** Moves a finished capture out of `.pending` and into its folder. */
    suspend fun commitRecording(
        result: RecorderEngine.Result,
        folder: String,
        displayName: String,
    ): Recording? = withContext(Dispatchers.IO) {
        val safeName = RecordingStorage.sanitiseName(displayName)
        val dir = storage.folderDir(folder)
        val target = storage.uniqueFile(dir, safeName, result.file.extension)
        if (!moveFile(result.file, target)) return@withContext null
        Peaks.sidecarFor(result.file).let { pendingPeaks ->
            if (pendingPeaks.isFile) moveFile(pendingPeaks, Peaks.sidecarFor(target))
        }

        if (folder.isNotBlank()) folderDao.insert(FolderEntity(folder))

        val entity = RecordingEntity(
            relPath = relPathOf(target),
            displayName = target.nameWithoutExtension,
            folder = folder,
            createdAt = System.currentTimeMillis(),
            durationMs = result.durationMs,
            sizeBytes = target.length(),
            format = result.config.container.name,
            sampleRate = result.config.sampleRate,
            bitDepth = result.config.bitDepth.bits,
            channels = result.config.channels.count,
            bookmarks = result.bookmarksMs.joinToString(","),
        )
        val id = recordingDao.insert(entity)
        entity.copy(id = id).toDomain()
    }

    /** Registers a file this app produced outside of a capture (e.g. a trim). */
    suspend fun registerFile(
        file: File,
        folder: String,
        durationMs: Long,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        format: String,
    ): Recording? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        if (folder.isNotBlank()) folderDao.insert(FolderEntity(folder))
        val entity = RecordingEntity(
            relPath = relPathOf(file),
            displayName = file.nameWithoutExtension,
            folder = folder,
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            sizeBytes = file.length(),
            format = format,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
        )
        val id = recordingDao.insert(entity)
        entity.copy(id = id).toDomain()
    }

    // ---- Edits --------------------------------------------------------------

    suspend fun rename(id: Long, newName: String): Boolean = withContext(Dispatchers.IO) {
        val row = recordingDao.byId(id) ?: return@withContext false
        val safe = RecordingStorage.sanitiseName(newName)
        if (safe == row.displayName) return@withContext true
        val current = storage.file(row.relPath)
        val target = storage.uniqueFile(current.parentFile ?: storage.root, safe, current.extension)
        if (!moveFile(current, target)) return@withContext false
        moveFile(Peaks.sidecarFor(current), Peaks.sidecarFor(target))
        recordingDao.update(
            row.copy(relPath = relPathOf(target), displayName = target.nameWithoutExtension),
        )
        true
    }

    suspend fun moveToFolder(id: Long, folder: String): Boolean = withContext(Dispatchers.IO) {
        val row = recordingDao.byId(id) ?: return@withContext false
        if (row.folder == folder) return@withContext true
        val current = storage.file(row.relPath)
        val target = storage.uniqueFile(storage.folderDir(folder), row.displayName, current.extension)
        if (!moveFile(current, target)) return@withContext false
        moveFile(Peaks.sidecarFor(current), Peaks.sidecarFor(target))
        if (folder.isNotBlank()) folderDao.insert(FolderEntity(folder))
        recordingDao.update(
            row.copy(relPath = relPathOf(target), folder = folder, displayName = target.nameWithoutExtension),
        )
        true
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        recordingDao.byId(id)?.let { recordingDao.update(it.copy(favorite = favorite)) }
    }

    suspend fun setNote(id: Long, note: String) = withContext(Dispatchers.IO) {
        recordingDao.byId(id)?.let { recordingDao.update(it.copy(note = note)) }
    }

    suspend fun setBookmarks(id: Long, bookmarks: List<Long>) = withContext(Dispatchers.IO) {
        recordingDao.byId(id)?.let {
            recordingDao.update(it.copy(bookmarks = bookmarks.sorted().joinToString(",")))
        }
    }

    /** Remembers where playback got to; zero means "start from the beginning". */
    suspend fun setPlaybackPosition(id: Long, positionMs: Long) = withContext(Dispatchers.IO) {
        recordingDao.byId(id)?.let { recordingDao.update(it.copy(lastPositionMs = positionMs)) }
    }

    // ---- Recycle bin --------------------------------------------------------

    suspend fun moveToTrash(id: Long): Boolean = withContext(Dispatchers.IO) {
        val row = recordingDao.byId(id) ?: return@withContext false
        if (row.deletedAt != null) return@withContext true
        val current = storage.file(row.relPath)
        val target = storage.uniqueFile(storage.trashDir, row.displayName, current.extension)
        if (current.isFile && !moveFile(current, target)) return@withContext false
        moveFile(Peaks.sidecarFor(current), Peaks.sidecarFor(target))
        recordingDao.update(
            row.copy(
                relPath = relPathOf(target),
                displayName = target.nameWithoutExtension,
                deletedAt = System.currentTimeMillis(),
                folderBeforeDelete = row.folder,
                folder = RecordingStorage.TRASH,
            ),
        )
        true
    }

    suspend fun restoreFromTrash(id: Long): Boolean = withContext(Dispatchers.IO) {
        val row = recordingDao.byId(id) ?: return@withContext false
        if (row.deletedAt == null) return@withContext true
        val folder = row.folderBeforeDelete.orEmpty()
        val current = storage.file(row.relPath)
        val target = storage.uniqueFile(storage.folderDir(folder), row.displayName, current.extension)
        if (current.isFile && !moveFile(current, target)) return@withContext false
        moveFile(Peaks.sidecarFor(current), Peaks.sidecarFor(target))
        if (folder.isNotBlank()) folderDao.insert(FolderEntity(folder))
        recordingDao.update(
            row.copy(
                relPath = relPathOf(target),
                displayName = target.nameWithoutExtension,
                folder = folder,
                deletedAt = null,
                folderBeforeDelete = null,
            ),
        )
        true
    }

    suspend fun deleteForever(id: Long) = withContext(Dispatchers.IO) {
        val row = recordingDao.byId(id) ?: return@withContext
        val file = storage.file(row.relPath)
        file.delete()
        Peaks.delete(file)
        recordingDao.deleteRow(id)
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        recordingDao.all().filter { it.deletedAt != null }.forEach { row ->
            val file = storage.file(row.relPath)
            file.delete()
            Peaks.delete(file)
            recordingDao.deleteRow(row.id)
        }
    }

    /** Drops bin entries past the retention window. Called on app start. */
    suspend fun purgeExpiredTrash(retentionDays: Int) = withContext(Dispatchers.IO) {
        if (retentionDays <= 0) return@withContext
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        recordingDao.trashOlderThan(cutoff).forEach { row ->
            val file = storage.file(row.relPath)
            file.delete()
            Peaks.delete(file)
            recordingDao.deleteRow(row.id)
        }
    }

    // ---- Folders ------------------------------------------------------------

    suspend fun createFolder(name: String) = withContext(Dispatchers.IO) {
        val safe = RecordingStorage.sanitiseName(name)
        storage.folderDir(safe).mkdirs()
        folderDao.insert(FolderEntity(safe))
    }

    /** Deletes the folder; its recordings move to the root rather than vanish. */
    suspend fun deleteFolder(name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        recordingDao.all().filter { it.folder == name && it.deletedAt == null }.forEach {
            moveToFolder(it.id, "")
        }
        folderDao.delete(name)
        storage.folderDir(name).delete()
    }

    suspend fun renameFolder(from: String, to: String) = withContext(Dispatchers.IO) {
        val safe = RecordingStorage.sanitiseName(to)
        if (from.isBlank() || safe == from) return@withContext
        folderDao.insert(FolderEntity(safe))
        storage.folderDir(safe).mkdirs()
        recordingDao.all().filter { it.folder == from && it.deletedAt == null }.forEach {
            moveToFolder(it.id, safe)
        }
        folderDao.delete(from)
        storage.folderDir(from).delete()
    }

    // ---- Reconciliation -----------------------------------------------------

    /**
     * Brings the index back in line with the filesystem: forgets rows whose file
     * is gone, and adopts audio files that appeared from outside the app.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val rows = recordingDao.all()
        val known = HashSet<String>(rows.size)
        rows.forEach { row ->
            val file = storage.file(row.relPath)
            if (!file.isFile) {
                recordingDao.deleteRow(row.id)
            } else {
                known += row.relPath
                if (row.sizeBytes != file.length()) {
                    recordingDao.update(row.copy(sizeBytes = file.length()))
                }
            }
        }

        val dirs = buildList {
            add("" to storage.root)
            storage.listFolderDirs().forEach { add(it.name to it) }
        }
        dirs.forEach { (folder, dir) ->
            dir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.extension.lowercase() !in AUDIO_EXTENSIONS) return@forEach
                val rel = relPathOf(file)
                if (rel in known) return@forEach
                if (recordingDao.byPath(rel) != null) return@forEach
                adopt(file, folder, rel)
            }
        }
        storage.listFolderDirs().forEach { folderDao.insert(FolderEntity(it.name)) }
    }

    private suspend fun adopt(file: File, folder: String, rel: String) {
        val probe = MediaProbe.probe(file)
        if (Peaks.load(file) == null) {
            PeakGenerator.generate(file)?.let { Peaks.save(file, it) }
        }
        recordingDao.insert(
            RecordingEntity(
                relPath = rel,
                displayName = file.nameWithoutExtension,
                folder = folder,
                createdAt = file.lastModified(),
                durationMs = probe.durationMs,
                sizeBytes = file.length(),
                format = file.extension.uppercase(),
                sampleRate = probe.sampleRate,
                bitDepth = probe.bitDepth,
                channels = probe.channels,
            ),
        )
    }

    /** Files left in `.pending` mean a crash mid-recording; keep them. */
    suspend fun recoverPending(): Int = withContext(Dispatchers.IO) {
        var recovered = 0
        storage.pendingDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.extension.lowercase() !in AUDIO_EXTENSIONS) return@forEach
            if (file.length() < MIN_RECOVERABLE_BYTES) {
                file.delete()
                Peaks.delete(file)
                return@forEach
            }
            val probe = MediaProbe.probe(file)
            val target = storage.uniqueFile(storage.root, "Recovered_${file.nameWithoutExtension}", file.extension)
            if (moveFile(file, target)) {
                moveFile(Peaks.sidecarFor(file), Peaks.sidecarFor(target))
                if (Peaks.load(target) == null) {
                    PeakGenerator.generate(target)?.let { Peaks.save(target, it) }
                }
                recordingDao.insert(
                    RecordingEntity(
                        relPath = relPathOf(target),
                        displayName = target.nameWithoutExtension,
                        folder = "",
                        createdAt = System.currentTimeMillis(),
                        durationMs = probe.durationMs,
                        sizeBytes = target.length(),
                        format = target.extension.uppercase(),
                        sampleRate = probe.sampleRate,
                        bitDepth = probe.bitDepth,
                        channels = probe.channels,
                    ),
                )
                recovered++
            }
        }
        recovered
    }

    // ---- Helpers ------------------------------------------------------------

    private fun relPathOf(file: File): String =
        file.absolutePath.removePrefix(storage.root.absolutePath).trimStart('/')

    private fun moveFile(from: File, to: File): Boolean {
        if (!from.isFile) return false
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        return runCatching {
            from.copyTo(to, overwrite = true)
            from.delete()
            true
        }.getOrDefault(false)
    }

    private fun RecordingEntity.toDomain() = Recording(
        id = id,
        displayName = displayName,
        folder = folder,
        file = storage.file(relPath),
        relPath = relPath,
        createdAt = createdAt,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        format = format,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        channels = channels,
        favorite = favorite,
        note = note,
        bookmarks = bookmarks.split(",").mapNotNull { it.trim().toLongOrNull() },
        lastPositionMs = lastPositionMs,
        deletedAt = deletedAt,
    )

    private companion object {
        val AUDIO_EXTENSIONS = setOf("wav", "m4a", "mp4", "ogg", "opus", "aac", "mp3", "flac", "3gp", "amr")
        const val MIN_RECOVERABLE_BYTES = 4096L
    }
}
