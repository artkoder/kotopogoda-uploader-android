package com.kotopogoda.uploader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kotopogoda.uploader.core.logging.LogManager
import com.kotopogoda.uploader.core.logging.LogsExportResult
import com.kotopogoda.uploader.core.logging.LogsExporter
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
        logManager = LogManager(context)
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
                assertTrue(
                    result.displayPath.startsWith("Download/Kotopogoda/"),
                    "Expected export path in Download/Kotopogoda but was ${result.displayPath}",
                )
                context.contentResolver.delete(result.uri, null, null)
            }
            LogsExportResult.NoLogs -> fail("Expected logs to export")
            is LogsExportResult.Error -> throw result.throwable
        }
    }
}
