package com.kotopogoda.uploader.core.data.upload

import android.net.Uri
import com.kotopogoda.uploader.core.work.UploadErrorKind

object UploadLog {
    fun message(
        action: String,
        itemId: Long? = null,
        photoId: String? = null,
        uri: Uri? = null,
        state: UploadItemState? = null,
        vararg details: Pair<String, Any?>,
    ): String {
        val parts = mutableListOf("action=$action")
        itemId?.let { parts += "itemId=$it" }
        photoId?.let { parts += "photoId=$it" }
        uri?.let { parts += "uri=$it" }
        state?.let { parts += "state=${it.name}" }
        details.forEach { (key, value) ->
            val normalized = when (value) {
                null -> null
                is UploadItemState -> value.name
                is UploadErrorKind -> value.name
                else -> value
            }
            if (normalized != null) {
                parts += "$key=$normalized"
            }
        }
        return parts.joinToString(", ")
    }
}
