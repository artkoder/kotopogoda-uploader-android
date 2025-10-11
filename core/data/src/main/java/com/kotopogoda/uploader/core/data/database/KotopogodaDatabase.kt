package com.kotopogoda.uploader.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kotopogoda.uploader.core.data.folder.FolderDao
import com.kotopogoda.uploader.core.data.folder.FolderEntity
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoEntity

@Database(
    entities = [FolderEntity::class, PhotoEntity::class],
    version = 2,
    exportSchema = false
)
abstract class KotopogodaDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    abstract fun photoDao(): PhotoDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photos` (" +
                        "`id` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`rel_path` TEXT, " +
                        "`sha256` TEXT NOT NULL, " +
                        "`exif_date` INTEGER NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`mime` TEXT NOT NULL DEFAULT 'image/jpeg', " +
                        "`status` TEXT NOT NULL DEFAULT 'new', " +
                        "`last_action_at` INTEGER, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_sha256` ON `photos` (`sha256`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_rel_path` ON `photos` (`rel_path`)")
            }
        }
    }
}
