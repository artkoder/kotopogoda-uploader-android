package com.kotopogoda.uploader.core.network.upload

enum class UploadWorkErrorKind(val rawValue: String) {
    NETWORK("network"),
    IO("io"),
    HTTP("http"),
    REMOTE_FAILURE("remoteFailure"),
    UNEXPECTED("unexpected");

    companion object {
        fun fromRawValue(raw: String?): UploadWorkErrorKind? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.rawValue == raw }
        }
    }
}
