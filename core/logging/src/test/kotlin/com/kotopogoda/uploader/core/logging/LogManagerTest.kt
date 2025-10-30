package com.kotopogoda.uploader.core.logging

import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.kotopogoda.uploader.core.logging.diagnostics.DiagnosticsProvider

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
        File(logsDir, "enhance-20240101.log").writeText("enhance log\n")

        val diagnosticsProvider = RecordingDiagnosticsProvider()
        val manager = LogManager(context, diagnosticsProvider, ioDispatcher = Dispatchers.Unconfined)
        val output = ByteArrayOutputStream()

        val hasLogs = manager.writeLogsArchive(output)

        assertTrue(hasLogs, "Expected archive to contain logs")
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes().toString(Charsets.UTF_8)
                entries[entry.name] = content
                zip.closeEntry()
            }
        }
        assertTrue(entries.containsKey("$APP_LOG_BASE_NAME.log"), "App log should be present in archive")
        assertTrue(entries.containsKey("$HTTP_LOG_BASE_NAME.log"), "HTTP log should be present in archive")
        assertTrue(entries.containsKey("enhance-20240101.log"), "Enhance log should be present in archive")
        val diagnostics = entries["diagnostics.json"] ?: error("diagnostics.json should be present in archive")
        val json = JSONObject(diagnostics)
        assertEquals("com.test.app", json.getJSONObject("app").getString("packageName"))
        assertEquals("1.2.3", json.getJSONObject("app").getString("versionName"))
        assertEquals("v9", json.getJSONObject("app").getString("contractVersion"))
        assertTrue(json.getJSONObject("settings").has("baseUrl"))
    }

    @Test
    fun `creates readme placeholder when no logs`() = runBlocking {
        val logsDir = File(tempDir, LOGS_DIR_NAME).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val diagnosticsProvider = RecordingDiagnosticsProvider()
        val manager = LogManager(context, diagnosticsProvider, ioDispatcher = Dispatchers.Unconfined)
        val output = ByteArrayOutputStream()

        val archiveCreated = manager.writeLogsArchive(output)

        assertTrue(archiveCreated, "Archive should be created even when no logs are present")
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes().toString(Charsets.UTF_8)
                entries[entry.name] = content
                zip.closeEntry()
            }
        }
        assertTrue(entries.containsKey("README-enhance.txt"), "README placeholder should be included in archive")
        val readme = entries.getValue("README-enhance.txt")
        assertTrue(readme.contains("Нет логов улучшения"), "README content should mention missing enhance logs")
        assertTrue(entries.containsKey("diagnostics.json"), "Diagnostics should still be exported")
    }

    private class RecordingDiagnosticsProvider : DiagnosticsProvider {
        override suspend fun writeDiagnostics(target: File) {
            val payload = """
                {
                  "app": {
                    "packageName": "com.test.app",
                    "versionName": "1.2.3",
                    "versionCode": 42,
                    "contractVersion": "v9"
                  },
                  "settings": {
                    "baseUrl": "https://example.test"
                  },
                  "permissions": {},
                  "network": {}
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
