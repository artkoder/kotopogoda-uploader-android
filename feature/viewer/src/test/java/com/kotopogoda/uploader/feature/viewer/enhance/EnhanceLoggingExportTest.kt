package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.ContextWrapper
import com.kotopogoda.uploader.core.logging.LogManager
import com.kotopogoda.uploader.core.logging.diagnostics.DiagnosticsProvider
import com.kotopogoda.uploader.feature.viewer.enhance.logging.EnhanceFileLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class EnhanceLoggingExportTest {

    private lateinit var tempDir: File
    private lateinit var context: TestContext

    @BeforeTest
    fun setUp() {
        tempDir = createTempDir(prefix = "enhance-logs-test")
        context = TestContext(tempDir)
    }

    @AfterTest
    fun tearDown() {
        EnhanceLogging.setVerboseLoggingEnabled(false)
        EnhanceLogging.setFileLogger(null)
        tempDir.deleteRecursively()
    }

    @Test
    fun `writes NDJSON logs when verbose logging is enabled`() {
        val fixedTimestamp = 1704110400000L
        val executor = Executors.newSingleThreadExecutor()
        val logger = EnhanceFileLogger(
            context = context,
            clock = { fixedTimestamp },
            executor = executor,
        )

        EnhanceLogging.setFileLogger(logger)
        EnhanceLogging.setVerboseLoggingEnabled(true)

        EnhanceLogging.logEvent("test_event_1", "key1" to "value1", "key2" to 42)
        EnhanceLogging.logEvent("test_event_2", mapOf("key3" to true, "key4" to "value4"))
        EnhanceLogging.logEvent("test_event_3")

        logger.shutdown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate")

        val logsDir = File(tempDir, "logs")
        assertTrue(logsDir.exists(), "Logs directory should exist")

        val logFiles = logsDir.listFiles { file ->
            file.isFile && file.name.startsWith("enhance") && file.extension == "log"
        } ?: emptyArray()

        assertEquals(1, logFiles.size, "Should have exactly one enhance log file")

        val logFile = logFiles.first()
        val lines = logFile.readLines()
        assertEquals(3, lines.size, "Should have three log entries")

        val event1 = JSONObject(lines[0])
        assertEquals("test_event_1", event1.getString("event"))
        assertEquals("value1", event1.getString("key1"))
        assertEquals(42, event1.getInt("key2"))
        assertEquals("enhance", event1.getString("category"))
        assertTrue(event1.has("timestamp"))

        val event2 = JSONObject(lines[1])
        assertEquals("test_event_2", event2.getString("event"))
        assertEquals(true, event2.getBoolean("key3"))
        assertEquals("value4", event2.getString("key4"))
        assertEquals("enhance", event2.getString("category"))

        val event3 = JSONObject(lines[2])
        assertEquals("test_event_3", event3.getString("event"))
        assertEquals("enhance", event3.getString("category"))
    }

    @Test
    fun `does not write logs when verbose logging is disabled`() {
        val fixedTimestamp = 1704110400000L
        val executor = Executors.newSingleThreadExecutor()
        val logger = EnhanceFileLogger(
            context = context,
            clock = { fixedTimestamp },
            executor = executor,
        )

        EnhanceLogging.setFileLogger(logger)
        EnhanceLogging.setVerboseLoggingEnabled(false)

        EnhanceLogging.logEvent("ignored_event", "key" to "value")

        logger.shutdown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate")

        val logsDir = File(tempDir, "logs")
        val logFiles = logsDir.listFiles { file ->
            file.isFile && file.name.startsWith("enhance") && file.extension == "log"
        } ?: emptyArray()

        assertTrue(logFiles.isEmpty() || logFiles.all { it.length() == 0L }, "No logs should be written when verbose is disabled")
    }

    @Test
    fun `LogManager includes enhance logs in archive`() = runBlocking {
        val fixedTimestamp = 1704110400000L
        val executor = Executors.newSingleThreadExecutor()
        val logger = EnhanceFileLogger(
            context = context,
            clock = { fixedTimestamp },
            executor = executor,
        )

        EnhanceLogging.setFileLogger(logger)
        EnhanceLogging.setVerboseLoggingEnabled(true)

        EnhanceLogging.logEvent("archive_test_event", "data" to "sample")

        logger.shutdown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate")

        val diagnosticsProvider = StubDiagnosticsProvider()
        val logManager = LogManager(context, diagnosticsProvider, ioDispatcher = Dispatchers.Unconfined)
        val output = ByteArrayOutputStream()

        val hasLogs = logManager.writeLogsArchive(output)

        assertTrue(hasLogs, "Archive should contain logs")

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes().toString(Charsets.UTF_8)
                entries[entry.name] = content
                zip.closeEntry()
            }
        }

        val enhanceLogEntry = entries.entries.firstOrNull { it.key.startsWith("enhance") && it.key.endsWith(".log") }
        assertTrue(enhanceLogEntry != null, "Archive should contain enhance log file")

        val enhanceContent = enhanceLogEntry!!.value
        assertTrue(enhanceContent.contains("archive_test_event"), "Enhance log should contain test event")
        assertTrue(enhanceContent.contains("\"data\":\"sample\""), "Enhance log should contain event data")

        assertTrue(entries.containsKey("diagnostics.json"), "Archive should contain diagnostics")
    }

    @Test
    fun `LogManager includes multiple log types in archive`() = runBlocking {
        val logsDir = File(tempDir, "logs").apply { mkdirs() }

        File(logsDir, "app-20240101.log").writeText("app log content\n")
        File(logsDir, "http-20240102.log").writeText("http log content\n")

        val fixedTimestamp = 1704110400000L
        val executor = Executors.newSingleThreadExecutor()
        val logger = EnhanceFileLogger(
            context = context,
            clock = { fixedTimestamp },
            executor = executor,
        )

        EnhanceLogging.setFileLogger(logger)
        EnhanceLogging.setVerboseLoggingEnabled(true)

        EnhanceLogging.logEvent("mixed_archive_event", "type" to "test")

        logger.shutdown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate")

        val diagnosticsProvider = StubDiagnosticsProvider()
        val logManager = LogManager(context, diagnosticsProvider, ioDispatcher = Dispatchers.Unconfined)
        val output = ByteArrayOutputStream()

        val hasLogs = logManager.writeLogsArchive(output)

        assertTrue(hasLogs, "Archive should contain logs")

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes().toString(Charsets.UTF_8)
                entries[entry.name] = content
                zip.closeEntry()
            }
        }

        assertTrue(entries.containsKey("app-20240101.log"), "Archive should contain app log")
        assertTrue(entries.containsKey("http-20240102.log"), "Archive should contain http log")

        val enhanceLogEntry = entries.entries.firstOrNull { it.key.startsWith("enhance") && it.key.endsWith(".log") }
        assertTrue(enhanceLogEntry != null, "Archive should contain enhance log")

        assertTrue(entries.containsKey("diagnostics.json"), "Archive should contain diagnostics")
        assertEquals(4, entries.size, "Archive should contain all log types plus diagnostics")
    }

    @Test
    fun `resets EnhanceLogging state after test to avoid pollution`() {
        val fixedTimestamp = 1704110400000L
        val executor = Executors.newSingleThreadExecutor()
        val logger = EnhanceFileLogger(
            context = context,
            clock = { fixedTimestamp },
            executor = executor,
        )

        EnhanceLogging.setFileLogger(logger)
        EnhanceLogging.setVerboseLoggingEnabled(true)

        assertTrue(EnhanceLogging.isVerboseLoggingEnabled(), "Verbose should be enabled")

        EnhanceLogging.setVerboseLoggingEnabled(false)
        EnhanceLogging.setFileLogger(null)

        assertFalse(EnhanceLogging.isVerboseLoggingEnabled(), "Verbose should be disabled after reset")

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor should terminate")
    }

    private class StubDiagnosticsProvider : DiagnosticsProvider {
        override suspend fun writeDiagnostics(target: File) {
            val payload = """
                {
                  "app": {
                    "packageName": "com.kotopogoda.uploader.test",
                    "versionName": "test-version",
                    "versionCode": 1,
                    "contractVersion": "v9"
                  },
                  "settings": {
                    "baseUrl": "https://test.example.com"
                  }
                }
            """.trimIndent()
            target.writeText(payload)
        }
    }

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
        override fun getCacheDir(): File = directory
    }
}
