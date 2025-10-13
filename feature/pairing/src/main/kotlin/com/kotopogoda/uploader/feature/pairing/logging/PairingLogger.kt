package com.kotopogoda.uploader.feature.pairing.logging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PairingLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .withZone(clock.zone)
    private val exportFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.getDefault())
        .withZone(clock.zone)

    fun log(message: String) {
        val timestamp = timestampFormatter.format(clock.instant())
        val sanitized = message.replace('\n', ' ')
        scope.launch {
            writeMutex.withLock {
                ensureLogFile().let { file ->
                    FileWriter(file, true).use { writer ->
                        writer.append('[')
                        writer.append(timestamp)
                        writer.append(']')
                        writer.append(' ')
                        writer.append(sanitized)
                        writer.appendLine()
                    }
                }
            }
        }
    }

    suspend fun exportToDownloads(): PairingLogExportResult = withContext(ioDispatcher) {
        val logFile = ensureLogFile(existsOnly = true)
            ?: return@withContext PairingLogExportResult.NoLogs
        if (logFile.length() == 0L) {
            return@withContext PairingLogExportResult.NoLogs
        }
        val resolver = context.contentResolver
        val fileName = "uploader-log-${exportFormatter.format(clock.instant())}.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(downloadsDir, fileName).absolutePath)
            }
        }
        val collectionUri = downloadsCollection()
        val destination = resolver.insert(collectionUri, values)
            ?: return@withContext PairingLogExportResult.Error(IOException("Не удалось создать файл"))
        return@withContext runCatching {
            resolver.openOutputStream(destination)?.use { output ->
                FileInputStream(logFile).use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Не удалось открыть поток для записи")
            PairingLogExportResult.Success(destination)
        }.getOrElse { error ->
            resolver.delete(destination, null, null)
            PairingLogExportResult.Error(error)
        }
    }

    private fun ensureLogFile(existsOnly: Boolean = false): File? {
        val directory = File(context.filesDir, "pairing-logs").apply { mkdirs() }
        val file = File(directory, LOG_FILE_NAME)
        if (existsOnly && !file.exists()) {
            return null
        }
        if (!existsOnly && !file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        return file
    }

    private fun downloadsCollection(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
    }

    companion object {
        private const val LOG_FILE_NAME = "pairing.log"
    }
}

sealed interface PairingLogExportResult {
    data class Success(val uri: Uri) : PairingLogExportResult
    data object NoLogs : PairingLogExportResult
    data class Error(val throwable: Throwable) : PairingLogExportResult
}
