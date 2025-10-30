package com.kotopogoda.uploader.feature.viewer.enhance

import java.util.concurrent.atomic.AtomicReference

/**
 * Глобальные настройки и результаты диагностики подсистемы улучшения фотографий.
 */
object EnhanceLogging {

    private val verboseEnabled = AtomicReference(false)
    private val probeSummaryRef = AtomicReference<ProbeSummary?>(null)

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseEnabled.set(enabled)
    }

    fun isVerboseLoggingEnabled(): Boolean = verboseEnabled.get()

    fun updateProbeSummary(summary: ProbeSummary) {
        probeSummaryRef.set(summary)
    }

    fun clearProbeSummary() {
        probeSummaryRef.set(null)
    }

    val probeSummary: ProbeSummary?
        get() = probeSummaryRef.get()

    data class ProbeSummary(
        val models: Map<String, ModelSummary>,
    )

    data class ModelSummary(
        val backend: String,
        val files: List<FileSummary>,
        val delegates: Map<String, DelegateSummary>,
    )

    data class FileSummary(
        val path: String,
        val bytes: Long,
        val checksum: String,
        val expectedChecksum: String?,
        val checksumOk: Boolean,
        val minBytes: Long,
        val minBytesOk: Boolean,
    )

    data class DelegateSummary(
        val available: Boolean,
        val warmupMillis: Long?,
        val error: String?,
    )
}
