package com.sclastro.recorder.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recordings",
    indices = [Index(value = ["relPath"], unique = true), Index(value = ["folder"])],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Path relative to the recordings root, including the file name. */
    val relPath: String,
    /** Name shown in the UI, without extension. */
    val displayName: String,
    /** Folder name; empty string means the root ("未分類"). */
    val folder: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val format: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val note: String = "",
    val favorite: Boolean = false,
    /** Non-null while the recording sits in the recycle bin. */
    val deletedAt: Long? = null,
    /** Folder to restore into when it comes back out of the bin. */
    val folderBeforeDelete: String? = null,
    /** Comma-separated millisecond offsets. */
    val bookmarks: String = "",
    /** Where playback got to, so a long recording can be resumed. */
    val lastPositionMs: Long = 0,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val name: String,
    val colorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
