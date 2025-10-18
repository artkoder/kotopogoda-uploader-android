package com.kotopogoda.uploader.core.data.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KotopogodaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KotopogodaDatabase::class.java,
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate7To8_addsAndPopulatesIdempotencyKey() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `upload_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `photo_id` TEXT NOT NULL,
                    `uri` TEXT NOT NULL DEFAULT '',
                    `display_name` TEXT NOT NULL DEFAULT 'photo.jpg',
                    `size` INTEGER NOT NULL DEFAULT 0,
                    `state` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER,
                    `last_error_kind` TEXT,
                    `http_code` INTEGER
                )
                """.trimIndent()
            )
            execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_state` ON `upload_items` (`state`)")
            execSQL("CREATE INDEX IF NOT EXISTS `index_upload_items_created_at` ON `upload_items` (`created_at`)")
            execSQL(
                """
                INSERT INTO `upload_items` (
                    `id`, `photo_id`, `uri`, `display_name`, `size`, `state`, `created_at`,
                    `updated_at`, `last_error_kind`, `http_code`
                ) VALUES (
                    1, 'photo-1', 'content://photos/1', 'photo-1.jpg', 1024, 'queued', 1000,
                    NULL, NULL, NULL
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            8,
            true,
            KotopogodaDatabase.MIGRATION_7_8
        ).apply {
            query("SELECT photo_id, idempotency_key FROM upload_items WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val photoId = cursor.getString(0)
                val key = cursor.getString(1)
                assertEquals(photoId, key)
                assertTrue(key.isNotBlank())
            }
            close()
        }
    }

    private companion object {
        const val TEST_DB = "kotopogoda-migration-test.db"
    }
}
