package com.kotopogoda.uploader.feature.viewer.enhance.logging

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.text.Charsets

/**
 * Файловый логер подсистемы Enhance, использующий отдельный поток и формат NDJSON.
 */
class EnhanceFileLogger(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EnhanceFileLogger").apply { isDaemon = true }
    },
) {

    private val directory: File = File(context.filesDir, "logs")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
        .withZone(ZoneOffset.UTC)
    private val ready = AtomicBoolean(true)

    @Volatile
    private var currentWriter: BufferedWriter? = null

    @Volatile
    private var currentFile: File? = null

    fun shutdown() {
        if (ready.compareAndSet(true, false)) {
            executor.execute {
                runCatching { currentWriter?.close() }
                currentWriter = null
                currentFile = null
                executor.shutdown()
            }
        }
    }

    fun log(event: String, payload: Map<String, Any?>) {
        if (!ready.get()) return
        executor.execute {
            try {
                writeEvent(event, payload)
            } catch (error: Throwable) {
                ready.set(false)
                runCatching { currentWriter?.close() }
                currentWriter = null
                currentFile = null
            }
        }
    }

    private fun writeEvent(event: String, payload: Map<String, Any?>) {
        ensureWriter()
        val timestamp = formatter.format(Instant.ofEpochMilli(clock()))
        val json = JSONObject()
        json.put("timestamp", timestamp)
        json.put("event", event)
        payload.forEach { (key, value) ->
            json.put(key, normalize(value))
        }
        val writer = currentWriter ?: return
        writer.append(json.toString())
        writer.append('\n')
        writer.flush()
        rotateIfNeeded()
    }

    private fun ensureWriter() {
        val existing = currentWriter
        if (existing != null) {
            if (currentFile?.length() ?: 0L < MAX_FILE_SIZE_BYTES) {
                return
            }
            rotate()
            return
        }
        directory.mkdirs()
        rotate()
    }

    private fun rotateIfNeeded() {
        val file = currentFile ?: return
        if (file.length() >= MAX_FILE_SIZE_BYTES) {
            rotate()
        }
    }

    private fun rotate() {
        runCatching { currentWriter?.close() }
        currentWriter = null
        currentFile = null
        directory.mkdirs()
        val nextFile = File(directory, "enhance-${FILE_NAME_FORMAT.format(clock())}.log")
        currentFile = nextFile
        currentWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(nextFile, true), Charsets.UTF_8))
        cleanupOldFiles()
    }

    private fun cleanupOldFiles() {
        val files = directory.listFiles { file ->
            file.isFile && file.name.startsWith("enhance") && file.extension == "log"
        }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { old -> runCatching { old.delete() } }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Number, is Boolean -> value
        is String -> value
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) ->
                if (k is String) {
                    put(k, normalize(v))
                }
            }
        }
        is Iterable<*> -> value.map { normalize(it) }
        else -> value.toString()
    }

    companion object {
        private const val MAX_FILES = 5
        private const val MAX_FILE_SIZE_BYTES: Long = 5L * 1024L * 1024L
        private val FILE_NAME_FORMAT = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    }
}
