package com.kotopogoda.uploader.core.data.util

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import timber.log.Timber

const val URI_READ_LOG_TAG: String = "UriAccess"

private const val MEDIA_DOCS_AUTH = "com.android.providers.media.documents"

fun isMediaUri(uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    val auth = uri.authority ?: return false
    return auth == MediaStore.AUTHORITY || auth == MEDIA_DOCS_AUTH
}

fun Uri.isMediaUri(): Boolean = isMediaUri(this)

fun ContentResolver.requireOriginalIfNeeded(uri: Uri): Uri {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return uri
    }
    if (!uri.isMediaUri()) {
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
