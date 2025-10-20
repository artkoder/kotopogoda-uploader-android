package com.kotopogoda.uploader.core.logging

import android.content.Context
import com.kotopogoda.uploader.core.logging.diagnostics.DiagnosticsProvider
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
    private val diagnosticsProvider: DiagnosticsProvider,
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
        val diagnosticsFile = File(context.cacheDir, DIAGNOSTICS_FILE_NAME)
        try {
            if (diagnosticsFile.exists()) {
                diagnosticsFile.delete()
            }
            val diagnosticsResult = runCatching { diagnosticsProvider.writeDiagnostics(diagnosticsFile) }
            if (diagnosticsResult.isFailure) {
                diagnosticsFile.writeText(
                    buildString {
                        append("{\n  \"error\": \"")
                        append(diagnosticsResult.exceptionOrNull().toJsonError().escapeForJson())
                        append("\"\n}")
                    }
                )
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
                if (diagnosticsFile.exists()) {
                    val entry = ZipEntry(DIAGNOSTICS_FILE_NAME)
                    zip.putNextEntry(entry)
                    BufferedInputStream(FileInputStream(diagnosticsFile)).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            diagnosticsFile.delete()
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

private const val DIAGNOSTICS_FILE_NAME = "diagnostics.json"

private fun String.escapeForJson(): String {
    return buildString(length) {
        for (character in this@escapeForJson) {
            when (character) {
                '\\' -> append("\\\\")
                '\"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}

private fun Throwable?.toJsonError(): String {
    if (this == null) return ""
    val name = this::class.java.simpleName.ifBlank { this::class.java.name }
    val message = message?.takeIf { it.isNotBlank() }
    return buildString {
        append(name)
        if (message != null) {
            append(": ")
            append(message)
        }
    }
}
