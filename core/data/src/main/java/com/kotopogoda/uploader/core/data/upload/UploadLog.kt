package com.kotopogoda.uploader.core.data.upload

import android.net.Uri
import com.kotopogoda.uploader.core.logging.diagnostic.DiagnosticContextProvider
import com.kotopogoda.uploader.core.work.UploadErrorKind
import java.util.concurrent.atomic.AtomicReference

object UploadLog {

    private val diagnosticContextProvider = AtomicReference<DiagnosticContextProvider?>()

    fun setDiagnosticContextProvider(provider: DiagnosticContextProvider) {
        diagnosticContextProvider.set(provider)
    }

    fun updateDiagnosticExtras(metadata: Map<String, String>) {
        diagnosticContextProvider.get()?.updateExtra(metadata)
    }

    fun message(
        category: String,
        action: String,
        photoId: String? = null,
        uri: Uri? = null,
        state: UploadItemState? = null,
        details: Array<out Pair<String, Any?>> = emptyArray(),
    ): String {
        val parts = mutableListOf<String>()
        parts += category
        val context = diagnosticContextProvider.get()?.snapshot().orEmpty()
        context.forEach { (key, value) ->
            appendPart(parts, key, value)
        }
        appendPart(parts, "action", action)
        photoId?.let { appendPart(parts, "photo_id", it) }
        uri?.let { appendPart(parts, "uri", it.toString()) }
        state?.let { appendPart(parts, "state", it.name) }
        details.forEach { (key, value) ->
            val normalized = normalizeValue(value) ?: return@forEach
            appendPart(parts, key, normalized)
        }
        return parts.joinToString(", ")
    }

    private fun appendPart(parts: MutableList<String>, key: String, value: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) {
            return
        }
        val masked = maskIfNeeded(normalizedKey, value)
        parts += "$normalizedKey=$masked"
    }

    private fun normalizeValue(value: Any?): String? = when (value) {
        null -> null
        is UploadItemState -> value.name
        is UploadErrorKind -> value.name
        is Uri -> value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }

    private fun maskIfNeeded(key: String, value: String): String {
        val lowercase = key.lowercase()
        return if (SECRET_PATTERNS.any { pattern -> lowercase.contains(pattern) }) {
            SECRET_MASK
        } else {
            value
        }
    }

    private const val SECRET_MASK = "***"
    private val SECRET_PATTERNS = listOf(
        "secret",
        "token",
        "key",
        "password",
        "auth",
    )
}
