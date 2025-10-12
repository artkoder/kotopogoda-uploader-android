package com.kotopogoda.uploader.core.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    suspend fun listLogFiles(): List<File> = withContext(ioDispatcher) {
        listLogFilesInternal()
    }

    suspend fun createLogsArchive(): File? = withContext(ioDispatcher) {
        val logs = listLogFilesInternal()
        if (logs.isEmpty()) {
            return@withContext null
        }
        val timestamp = LocalDateTime.now().format(timestampFormatter)
        val archive = File(context.cacheDir, "logs-$timestamp.zip")
        if (archive.exists()) {
            archive.delete()
        }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(archive))).use { zip ->
            logs.forEach { file ->
                ZipEntry(file.name).let { entry ->
                    zip.putNextEntry(entry)
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
        archive
    }

    fun logsDirectory(): File = File(context.filesDir, LOGS_DIR_NAME)

    private fun listLogFilesInternal(): List<File> {
        val directory = logsDirectory()
        if (!directory.exists()) {
            return emptyList()
        }
        return directory.listFiles { file -> file.isFile && file.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    companion object {
        private const val LOGS_DIR_NAME = "logs"
    }
}
