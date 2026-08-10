package com.sclastro.recorder.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeById(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings")
    suspend fun all(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE relPath = :relPath LIMIT 1")
    suspend fun byPath(relPath: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecordingEntity): Long

    @Update
    suspend fun update(entity: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("SELECT * FROM recordings WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashOlderThan(cutoff: Long): List<RecordingEntity>

    @Query("SELECT COUNT(*) FROM recordings WHERE deletedAt IS NOT NULL")
    fun observeTrashCount(): Flow<Int>
}

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY name")
    fun observeAll(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE name = :name")
    suspend fun delete(name: String)

    @Query("UPDATE folders SET name = :to WHERE name = :from")
    suspend fun rename(from: String, to: String)

    @Query("UPDATE recordings SET folder = :to, relPath = :toPrefix || substr(relPath, length(:fromPrefix) + 1) WHERE folder = :from AND deletedAt IS NULL")
    suspend fun repointRecordings(from: String, to: String, fromPrefix: String, toPrefix: String)
}
