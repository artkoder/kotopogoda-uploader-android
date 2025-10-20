package com.kotopogoda.uploader.core.logging.diagnostics

import java.io.File

interface DiagnosticsProvider {
    suspend fun writeDiagnostics(target: File)
}
