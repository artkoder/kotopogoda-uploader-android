package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.Context
import java.io.File
import timber.log.Timber

class NativeEnhanceCrashLoopDetector(
    private val markerFile: File,
) {

    constructor(context: Context) : this(File(context.filesDir, MARKER_FILE_NAME))

    fun isCrashLoopSuspected(): Boolean = markerFile.exists()

    fun markInitializationStarted() {
        runCatching {
            markerFile.parentFile?.mkdirs()
            markerFile.writeText(System.currentTimeMillis().toString())
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Не удалось записать crash loop маркер")
        }
    }

    fun clearMarker() {
        if (!markerFile.exists()) {
            return
        }
        runCatching {
            markerFile.delete()
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Не удалось удалить crash loop маркер")
        }
    }

    companion object {
        private const val TAG = "NativeEnhanceCrashLoop"
        private const val MARKER_FILE_NAME = "native_enhance_crash.marker"
    }
}
