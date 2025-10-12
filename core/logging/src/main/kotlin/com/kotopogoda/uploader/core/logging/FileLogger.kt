package com.kotopogoda.uploader.core.logging

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class FileLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Timber.Tree() {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    @Volatile
    private var currentFile: File = File(logsDirectory(), CURRENT_FILE_NAME)

    fun logsDirectory(): File = File(context.filesDir, LOGS_DIR_NAME).apply { mkdirs() }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val level = priorityLabel(priority)
        val formattedMessage = buildString {
            append("[")
            append(timestamp)
            append("][")
            append(level)
            append("] ")
            if (!tag.isNullOrBlank()) {
                append(tag)
                append(": ")
            }
            append(message)
        }
        scope.launch {
            writeMutex.withLock {
                rotateIfNeeded()
                writeLine(formattedMessage)
                if (t != null) {
                    writeLine(Log.getStackTraceString(t))
                }
                rotateIfNeeded()
            }
        }
    }

    private fun writeLine(text: String) {
        val file = ensureCurrentFile()
        FileWriter(file, true).use { writer ->
            writer.appendLine(text)
        }
    }

    private fun ensureCurrentFile(): File {
        var file = currentFile
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        if (file.length() >= MAX_FILE_SIZE_BYTES) {
            rotateFiles()
            file = currentFile
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        }
        return file
    }

    private fun rotateIfNeeded() {
        val file = currentFile
        if (file.exists() && file.length() >= MAX_FILE_SIZE_BYTES) {
            rotateFiles()
        }
    }

    private fun rotateFiles() {
        val directory = logsDirectory()
        for (index in MAX_FILES downTo 1) {
            val target = File(directory, "app-$index.log")
            if (target.exists() && index == MAX_FILES) {
                target.delete()
            }
        }
        for (index in MAX_FILES - 1 downTo 1) {
            val source = File(directory, "app-${index - 1}.log")
            if (source.exists()) {
                val destination = File(directory, "app-$index.log")
                destination.delete()
                source.renameTo(destination)
            }
        }
        val zeroFile = File(directory, CURRENT_FILE_NAME)
        if (zeroFile.exists()) {
            zeroFile.delete()
        }
        currentFile = zeroFile
    }

    private fun priorityLabel(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> priority.toString()
    }

    companion object {
        private const val LOGS_DIR_NAME = "logs"
        private const val CURRENT_FILE_NAME = "app-0.log"
        private const val MAX_FILES = 5
        private const val MAX_FILE_SIZE_BYTES: Long = 2 * 1024 * 1024 // 2 MB
    }
}
