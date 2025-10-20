package com.kotopogoda.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kotopogoda.uploader.core.logging.LogManager
import com.kotopogoda.uploader.core.logging.LogsExportResult
import com.kotopogoda.uploader.core.logging.LogsExporter
import com.kotopogoda.uploader.core.logging.diagnostics.DiagnosticsProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogsExporterTest {

    private lateinit var context: Context
    private lateinit var logManager: LogManager
    private lateinit var logsExporter: LogsExporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logManager = LogManager(context, TestDiagnosticsProvider())
        logsExporter = LogsExporter(context, logManager)
        logManager.logsDirectory().deleteRecursively()
        logManager.logsDirectory().mkdirs()
    }

    @After
    fun tearDown() {
        logManager.logsDirectory().deleteRecursively()
    }

    @Test
    fun exportWritesToDownloadsDirectory() = runBlocking {
        val sampleLog = File(logManager.logsDirectory(), "app.log")
        sampleLog.writeText("test log entry")

        when (val result = logsExporter.export()) {
            is LogsExportResult.Success -> {
                val directoryPath = logsExporter.publicDirectoryDisplayPath()
                assertTrue(
                    result.displayPath.startsWith(directoryPath),
                    "Expected export path in $directoryPath but was ${result.displayPath}",
                )
                val fileName = result.displayPath.removePrefix(directoryPath)
                assertTrue(
                    fileName.matches(Regex("logs-\\d{8}-\\d{4}\\.zip")),
                    "Unexpected export file name: $fileName",
                )
                context.contentResolver.delete(result.uri, null, null)
            }
            LogsExportResult.NoLogs -> fail("Expected logs to export")
            is LogsExportResult.Error -> throw result.throwable
        }
    }

    @Test
    fun publicDirectoryDisplayPathHasTrailingSlash() {
        val path = logsExporter.publicDirectoryDisplayPath()
        assertTrue(path.endsWith('/'))
        assertTrue(path.startsWith("Download/Kotopogoda"))
    }
}

private class TestDiagnosticsProvider : DiagnosticsProvider {
    override suspend fun writeDiagnostics(target: File) {
        target.writeText("{}")
    }
}
