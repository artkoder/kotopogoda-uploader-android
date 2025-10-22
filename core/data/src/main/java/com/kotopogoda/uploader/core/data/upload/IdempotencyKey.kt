package com.kotopogoda.uploader.core.data.upload

const val IDEMPOTENCY_KEY_PREFIX: String = "upload:"

fun idempotencyKeyFromContentSha256(contentSha256: String): String {
    return IDEMPOTENCY_KEY_PREFIX + contentSha256
}

fun contentSha256FromIdempotencyKey(idempotencyKey: String?): String? {
    if (idempotencyKey.isNullOrBlank()) return null
    if (!idempotencyKey.startsWith(IDEMPOTENCY_KEY_PREFIX)) return null
    val digest = idempotencyKey.removePrefix(IDEMPOTENCY_KEY_PREFIX)
    return digest.takeIf { it.isNotBlank() }
}
