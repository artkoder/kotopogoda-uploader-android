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
    version = 8,
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
                    """
                    CREATE TABLE IF NOT EXISTS `upload_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `photo_id` TEXT NOT NULL,
                        `idempotency_key` TEXT NOT NULL DEFAULT '',
                        `uri` TEXT NOT NULL DEFAULT '',
                        `display_name` TEXT NOT NULL DEFAULT 'photo.jpg',
                        `size` INTEGER NOT NULL DEFAULT 0,
                        `state` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER,
                        `last_error_kind` TEXT,
                        `http_code` INTEGER
                    )
                    """
                        .trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_state` ON `upload_items` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_created_at` ON `upload_items` (`created_at`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = getTableColumns(db, "upload_items")
                if ("updated_at" !in columns) {
                    db.execSQL("ALTER TABLE `upload_items` ADD COLUMN `updated_at` INTEGER")
                }
                if ("last_error_kind" !in columns) {
                    db.execSQL("ALTER TABLE `upload_items` ADD COLUMN `last_error_kind` TEXT")
                }
                if ("http_code" !in columns) {
                    db.execSQL("ALTER TABLE `upload_items` ADD COLUMN `http_code` INTEGER")
                }
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = getTableColumns(db, "upload_items")
                if ("uri" !in columns) {
                    db.execSQL("ALTER TABLE `upload_items` ADD COLUMN `uri` TEXT NOT NULL DEFAULT ''")
                }
                if ("display_name" !in columns) {
                    db.execSQL("ALTER TABLE `upload_items` ADD COLUMN `display_name` TEXT NOT NULL DEFAULT 'photo.jpg'")
                }
                if ("size" !in columns) {
                    db.execSQL("ALTER TABLE `upload_items` ADD COLUMN `size` INTEGER NOT NULL DEFAULT 0")
                }

                db.execSQL(
                    """
                    UPDATE upload_items
                    SET uri = (
                        SELECT uri FROM photos WHERE photos.id = upload_items.photo_id
                    )
                    WHERE uri = ''
                        AND EXISTS (
                            SELECT 1 FROM photos WHERE photos.id = upload_items.photo_id
                        )
                    """
                        .trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE upload_items
                    SET size = (
                        SELECT size FROM photos WHERE photos.id = upload_items.photo_id
                    )
                    WHERE size = 0
                        AND EXISTS (
                            SELECT 1 FROM photos WHERE photos.id = upload_items.photo_id
                        )
                    """
                        .trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE upload_items
                    SET display_name = COALESCE(
                        (
                            SELECT rel_path FROM photos WHERE photos.id = upload_items.photo_id
                        ),
                        (
                            SELECT uri FROM photos WHERE photos.id = upload_items.photo_id
                        ),
                        display_name
                    )
                    WHERE (TRIM(display_name) = '' OR display_name = 'photo.jpg')
                        AND EXISTS (
                            SELECT 1 FROM photos WHERE photos.id = upload_items.photo_id
                        )
                    """
                        .trimIndent()
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = getTableColumns(db, "upload_items")
                if ("idempotency_key" !in columns) {
                    db.execSQL("ALTER TABLE `upload_items` ADD COLUMN `idempotency_key` TEXT NOT NULL DEFAULT ''")
                }

                db.execSQL(
                    """
                    UPDATE upload_items
                    SET idempotency_key = photo_id
                    WHERE TRIM(idempotency_key) = ''
                    """
                        .trimIndent()
                )
            }
        }

        private fun getTableColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIndex))
                }
            }
            return columns
        }
    }
}
