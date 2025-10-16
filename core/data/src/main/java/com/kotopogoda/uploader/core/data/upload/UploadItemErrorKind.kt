package com.kotopogoda.uploader.core.data.upload

enum class UploadItemErrorKind(val rawValue: String) {
    NETWORK("network"),
    IO("io"),
    HTTP("http"),
    REMOTE_FAILURE("remoteFailure"),
    UNEXPECTED("unexpected");

    companion object {
        fun fromRawValue(rawValue: String?): UploadItemErrorKind? {
            if (rawValue.isNullOrBlank()) return null
            return entries.firstOrNull { it.rawValue == rawValue }
        }
    }
}
