package com.sclastro.recorder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RecordingEntity::class, FolderEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RecorderDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun folderDao(): FolderDao

    companion object {
        fun build(context: Context): RecorderDatabase =
            Room.databaseBuilder(context.applicationContext, RecorderDatabase::class.java, "recorder.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
