package com.kotopogoda.uploader.core.logging

import android.content.Context
import java.io.File

internal fun ensureLogsDirectory(context: Context): File =
    File(context.filesDir, LOGS_DIR_NAME).apply { mkdirs() }
