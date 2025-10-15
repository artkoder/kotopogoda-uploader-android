package com.kotopogoda.uploader.core.logging

import java.io.File
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class RotatingLogWriterTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "logs-test")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `limits file count and size`() {
        val writer = RotatingLogWriter(
            directoryProvider = { tempDir },
            fileBaseName = APP_LOG_BASE_NAME,
            maxFiles = LOG_MAX_FILES,
            maxFileSizeBytes = 1_024L,
        )

        val payload = "x".repeat(900)
        repeat(30) {
            writer.appendLine(payload)
        }

        val files = tempDir.listFiles { file -> file.extension == "log" }?.toList().orEmpty()
        assertTrue(files.isNotEmpty(), "Expected rotated log files to be created")
        assertTrue(files.size <= LOG_MAX_FILES, "Expected at most $LOG_MAX_FILES files but was ${files.size}")
        files.forEach { file ->
            assertTrue(
                file.length() <= 1_024L,
                "Log file ${file.name} exceeds the max size: ${file.length()}"
            )
        }

        val names = files.map(File::getName)
        assertTrue(names.contains("$APP_LOG_BASE_NAME.log"), "Expected current log file to exist")
    }
}
