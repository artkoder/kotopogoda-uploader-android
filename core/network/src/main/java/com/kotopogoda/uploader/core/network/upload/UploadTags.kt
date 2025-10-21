package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import android.util.Base64
import androidx.work.WorkInfo
import java.nio.charset.StandardCharsets

object UploadTags {
    const val TAG_UPLOAD: String = "upload"
    const val TAG_POLL: String = "poll"
    const val TAG_DRAIN: String = "drain"
    private const val PREFIX_UNIQUE = "unique:"
    private const val PREFIX_URI = "uri:"
    private const val PREFIX_DISPLAY_NAME = "display:"
    private const val PREFIX_KEY = "key:"
    private const val PREFIX_KIND = "kind:"

    fun uniqueTag(value: String): String = PREFIX_UNIQUE + encode(value)
    fun uriTag(value: String): String = PREFIX_URI + encode(value)
    fun displayNameTag(value: String): String = PREFIX_DISPLAY_NAME + encode(value)
    fun keyTag(value: String): String = PREFIX_KEY + encode(value)
    fun kindTag(kind: UploadWorkKind): String = PREFIX_KIND + encode(kind.rawValue)

    fun metadataFrom(workInfo: WorkInfo): UploadWorkMetadata {
        val tags = workInfo.tags
        val unique = decodeWithPrefix(tags, PREFIX_UNIQUE)
        val uri = decodeWithPrefix(tags, PREFIX_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val displayName = decodeWithPrefix(tags, PREFIX_DISPLAY_NAME)
        val key = decodeWithPrefix(tags, PREFIX_KEY)
        val kind = decodeWithPrefix(tags, PREFIX_KIND)?.let(UploadWorkKind::fromRawValue)
            ?: UploadWorkKind.UPLOAD
        return UploadWorkMetadata(
            uniqueName = unique,
            uri = uri,
            displayName = displayName,
            idempotencyKey = key,
            kind = kind
        )
    }

    private fun decodeWithPrefix(tags: Set<String>, prefix: String): String? {
        val encoded = tags.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
        return decode(encoded)
    }

    private fun encode(value: String): String {
        return Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decode(value: String): String? {
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            String(bytes, StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

data class UploadWorkMetadata(
    val uniqueName: String?,
    val uri: Uri?,
    val displayName: String?,
    val idempotencyKey: String?,
    val kind: UploadWorkKind
)

enum class UploadWorkKind(val rawValue: String) {
    UPLOAD("upload"),
    POLL("poll"),
    DRAIN("drain");

    companion object {
        fun fromRawValue(raw: String): UploadWorkKind? {
            return entries.firstOrNull { it.rawValue == raw }
        }
    }
}
