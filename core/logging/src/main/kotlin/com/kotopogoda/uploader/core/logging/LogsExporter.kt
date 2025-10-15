package com.kotopogoda.uploader.core.logging

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LogsExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logManager: LogManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    suspend fun export(): LogsExportResult = withContext(ioDispatcher) {
        val displayName = "kotopogoda-logs-${LocalDateTime.now().format(timestampFormatter)}.zip"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportWithMediaStore(displayName)
            } else {
                exportLegacy(displayName)
            }
        }.getOrElse { throwable ->
            LogsExportResult.Error(throwable)
        }
    }

    private suspend fun exportWithMediaStore(displayName: String): LogsExportResult {
        val resolver = context.contentResolver
        val relativePath = "Download/Kotopogoda"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, ZIP_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return LogsExportResult.Error(IllegalStateException("Unable to create export destination"))
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                val hasLogs = logManager.writeLogsArchive(output)
                if (!hasLogs) {
                    resolver.delete(uri, null, null)
                    return LogsExportResult.NoLogs
                }
            } ?: return LogsExportResult.Error(IllegalStateException("Unable to open export destination"))
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                .also { resolver.update(uri, it, null, null) }
            LogsExportResult.Success(uri, "$relativePath/$displayName")
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            LogsExportResult.Error(error)
        }
    }

    private suspend fun exportLegacy(displayName: String): LogsExportResult {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsDir, "Kotopogoda").apply { mkdirs() }
        val outputFile = File(targetDir, displayName)
        FileOutputStream(outputFile).use { output ->
            val hasLogs = logManager.writeLogsArchive(output)
            if (!hasLogs) {
                outputFile.delete()
                return LogsExportResult.NoLogs
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(outputFile.absolutePath),
            arrayOf(ZIP_MIME_TYPE),
            null,
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile)
        return LogsExportResult.Success(uri, "Download/Kotopogoda/$displayName")
    }
}

sealed interface LogsExportResult {
    data class Success(val uri: Uri, val displayPath: String) : LogsExportResult
    data object NoLogs : LogsExportResult
    data class Error(val throwable: Throwable) : LogsExportResult
}

private const val ZIP_MIME_TYPE = "application/zip"
