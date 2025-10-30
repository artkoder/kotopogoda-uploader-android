package com.kotopogoda.uploader.feature.viewer.enhance

import com.kotopogoda.uploader.feature.viewer.enhance.logging.EnhanceFileLogger
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Глобальные настройки и результаты диагностики подсистемы улучшения фотографий.
 */
object EnhanceLogging {

    private val verboseEnabled = AtomicReference(false)
    private val probeSummaryRef = AtomicReference<ProbeSummary?>(null)
    private val fileLoggerRef = AtomicReference<EnhanceFileLogger?>(null)

    fun setVerboseLoggingEnabled(enabled: Boolean) {
        verboseEnabled.set(enabled)
    }

    fun isVerboseLoggingEnabled(): Boolean = verboseEnabled.get()

    fun setFileLogger(logger: EnhanceFileLogger?) {
        val previous = fileLoggerRef.getAndSet(logger)
        if (previous !== logger) {
            previous?.shutdown()
        }
    }

    fun logEvent(event: String, payload: Map<String, Any?>) {
        if (!isVerboseLoggingEnabled()) {
            return
        }
        val logger = fileLoggerRef.get() ?: return
        val enriched = payload.toMutableMap()
        enriched["category"] = "enhance"
        logger.log(event, enriched)
    }

    fun logEvent(event: String, vararg details: Pair<String, Any?>) {
        if (details.isEmpty()) {
            logEvent(event, emptyMap())
            return
        }
        val payload = LinkedHashMap<String, Any?>(details.size)
        details.forEach { (key, value) ->
            if (key.isNotBlank()) {
                payload[key] = value
            }
        }
        logEvent(event, payload)
    }

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
