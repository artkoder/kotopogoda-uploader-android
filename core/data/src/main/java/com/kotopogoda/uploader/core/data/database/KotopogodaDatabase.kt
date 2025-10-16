package com.kotopogoda.uploader.core.data.database

import android.content.Intent
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kotopogoda.uploader.core.data.folder.FolderDao
import com.kotopogoda.uploader.core.data.folder.FolderEntity
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoEntity
import com.kotopogoda.uploader.core.data.upload.UploadItemDao
import com.kotopogoda.uploader.core.data.upload.UploadItemEntity

@Database(
    entities = [FolderEntity::class, PhotoEntity::class, UploadItemEntity::class],
    version = 6,
    exportSchema = false
)
abstract class KotopogodaDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    abstract fun photoDao(): PhotoDao

    abstract fun uploadItemDao(): UploadItemDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photos_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`rel_path` TEXT, " +
                        "`sha256` TEXT NOT NULL, " +
                        "`exif_date` INTEGER, " +
                        "`size` INTEGER NOT NULL, " +
                        "`mime` TEXT NOT NULL DEFAULT 'image/jpeg', " +
                        "`status` TEXT NOT NULL DEFAULT 'new', " +
                        "`last_action_at` INTEGER, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `photos_new`(" +
                        "`id`, `uri`, `rel_path`, `sha256`, `exif_date`, `size`, `mime`, `status`, `last_action_at`" +
                        ") SELECT `id`, `uri`, `rel_path`, `sha256`, `exif_date`, `size`, `mime`, `status`, `last_action_at` FROM `photos`"
                )
                db.execSQL("DROP TABLE `photos`")
                db.execSQL("ALTER TABLE `photos_new` RENAME TO `photos`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_photos_sha256` ON `photos` (`sha256`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_rel_path` ON `photos` (`rel_path`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `folder` ADD COLUMN `flags` INTEGER NOT NULL DEFAULT ${Intent.FLAG_GRANT_READ_URI_PERMISSION}"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `upload_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`unique_name` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`idempotency_key` TEXT NOT NULL, " +
                        "`display_name` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`error_kind` TEXT, " +
                        "`error_http_code` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL" +
                        ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_state` ON `upload_items` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_created_at` ON `upload_items` (`created_at`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_items_unique_name` ON `upload_items` (`unique_name`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `upload_items_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`unique_name` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`idempotency_key` TEXT NOT NULL, " +
                        "`display_name` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`error_kind` TEXT, " +
                        "`error_http_code` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL" +
                        ")"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_new_state` ON `upload_items_new` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_new_created_at` ON `upload_items_new` (`created_at`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_upload_items_new_unique_name` ON `upload_items_new` (`unique_name`)")
                db.execSQL(
                    "INSERT INTO `upload_items_new`(" +
                        "`id`, `unique_name`, `uri`, `idempotency_key`, `display_name`, `state`, `error_kind`, `error_http_code`, `created_at`, `updated_at`" +
                        ") SELECT " +
                        "`id`, `photo_id`, `photo_id`, `photo_id`, '', `state`, NULL, NULL, `created_at`, `created_at`" +
                        " FROM `upload_items`"
                )
                db.execSQL("DROP TABLE `upload_items`")
                db.execSQL("ALTER TABLE `upload_items_new` RENAME TO `upload_items`")
            }
        }
    }
}
