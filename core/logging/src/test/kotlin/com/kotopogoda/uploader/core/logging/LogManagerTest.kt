package com.kotopogoda.uploader.core.logging

import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class LogManagerTest {

    private lateinit var tempDir: File
    private lateinit var context: TestContext

    @BeforeTest
    fun setUp() {
        tempDir = createTempDir(prefix = "logs-manager")
        context = TestContext(tempDir)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `includes app and http logs in archive`() = runBlocking {
        val logsDir = File(tempDir, LOGS_DIR_NAME).apply { mkdirs() }
        File(logsDir, "$APP_LOG_BASE_NAME.log").writeText("app log\n")
        File(logsDir, "$HTTP_LOG_BASE_NAME.log").writeText("http log\n")

        val manager = LogManager(context, ioDispatcher = Dispatchers.Unconfined)
        val output = ByteArrayOutputStream()

        val hasLogs = manager.writeLogsArchive(output)

        assertTrue(hasLogs, "Expected archive to contain logs")
        val entries = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry?.name }.toSet()
        }
        assertTrue(entries.contains("$APP_LOG_BASE_NAME.log"), "App log should be present in archive")
        assertTrue(entries.contains("$HTTP_LOG_BASE_NAME.log"), "HTTP log should be present in archive")
    }

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
    }
}
