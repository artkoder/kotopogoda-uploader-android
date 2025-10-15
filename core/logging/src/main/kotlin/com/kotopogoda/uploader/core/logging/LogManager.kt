package com.kotopogoda.uploader.core.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
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

    suspend fun listLogFiles(): List<File> = withContext(ioDispatcher) {
        listLogFilesInternal()
    }

    suspend fun writeLogsArchive(outputStream: OutputStream): Boolean = withContext(ioDispatcher) {
        val logs = listLogFilesInternal()
        if (logs.isEmpty()) {
            return@withContext false
        }
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            logs.forEach { file ->
                val entry = ZipEntry(file.name)
                zip.putNextEntry(entry)
                BufferedInputStream(FileInputStream(file)).use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
        true
    }

    fun logsDirectory(): File = ensureLogsDirectory(context)

    fun logsDirectoryPath(): String = logsDirectory().absolutePath

    private fun listLogFilesInternal(): List<File> {
        val directory = logsDirectory()
        if (!directory.exists()) {
            return emptyList()
        }
        return directory.listFiles { file -> file.isFile && file.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
