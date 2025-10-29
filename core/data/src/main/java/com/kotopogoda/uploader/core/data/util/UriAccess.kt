package com.kotopogoda.uploader.core.data.util

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import timber.log.Timber

const val URI_READ_LOG_TAG: String = "UriAccess"

fun ContentResolver.requireOriginalIfNeeded(uri: Uri): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return uri
    }
    if (!MediaStore.isMediaUri(uri)) {
        return uri
    }
    return runCatching { MediaStore.setRequireOriginal(uri) }
        .getOrElse { error ->
            Timber.tag(URI_READ_LOG_TAG).d(
                error,
                "setRequireOriginal failed for %s",
                uri,
            )
            uri
        }
}

fun ContentResolver.hasPersistedReadPermission(vararg candidates: Uri): Boolean {
    if (candidates.isEmpty()) {
        return false
    }
    val normalized = candidates.map(Uri::toString).toSet()
    return persistedUriPermissions.any { permission ->
        permission.isReadPermission && permission.uri.toString() in normalized
    }
}

fun ContentResolver.logUriReadDebug(action: String, original: Uri, normalized: Uri) {
    val persistedRead = hasPersistedReadPermission(original, normalized)
    Timber.tag(URI_READ_LOG_TAG).d(
        "%s: scheme=%s requireOriginal=%s persistedRead=%s",
        action,
        normalized.scheme,
        normalized != original,
        persistedRead,
    )
}
