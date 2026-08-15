package com.sclastro.recorder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecordingEntity::class, FolderEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class RecorderDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun folderDao(): FolderDao

    companion object {
        /**
         * Adds the resume position. Written out rather than falling back to a
         * destructive migration, which would drop favourites, notes and
         * bookmarks — data that exists nowhere else.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN lastPositionMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): RecorderDatabase =
            Room.databaseBuilder(context.applicationContext, RecorderDatabase::class.java, "recorder.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
