package com.kotopogoda.uploader.core.logging

import java.io.File
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class RotatingLogWriter(
    private val directoryProvider: () -> File,
    private val fileBaseName: String,
    private val maxFiles: Int,
    private val maxFileSizeBytes: Long,
) {

    private val lock = ReentrantLock()
    private var currentFile: File = File(directoryProvider(), fileName(0))
    private val newlineBytes = System.lineSeparator().toByteArray(StandardCharsets.UTF_8).size

    init {
        logsDirectory()
    }

    fun logsDirectory(): File = directoryProvider().apply { mkdirs() }

    fun appendLine(text: String) {
        val estimatedBytes = text.toByteArray(StandardCharsets.UTF_8).size + newlineBytes
        lock.withLock {
            rotateIfNeeded(extraBytes = estimatedBytes.toLong())
            writeLine(text)
            rotateIfNeeded()
        }
    }

    private fun writeLine(text: String) {
        val file = ensureCurrentFile()
        FileWriter(file, true).use { writer ->
            writer.appendLine(text)
        }
    }

    private fun ensureCurrentFile(): File {
        val file = currentFile
        if (!file.exists()) {
            logsDirectory()
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        return file
    }

    private fun rotateIfNeeded(extraBytes: Long = 0) {
        val file = currentFile
        val currentLength = if (file.exists()) file.length() else 0L
        if (currentLength + extraBytes >= maxFileSizeBytes) {
            rotateFiles()
        }
    }

    private fun rotateFiles() {
        val directory = logsDirectory()
        for (index in maxFiles - 1 downTo 1) {
            val source = File(directory, fileName(index - 1))
            if (source.exists()) {
                val destination = File(directory, fileName(index))
                if (destination.exists()) {
                    destination.delete()
                }
                source.renameTo(destination)
            }
        }
        currentFile = File(directory, fileName(0))
        if (currentFile.exists()) {
            currentFile.delete()
        }
    }

    private fun fileName(index: Int): String =
        if (index == 0) {
            "$fileBaseName.log"
        } else {
            "$fileBaseName-$index.log"
        }
}
