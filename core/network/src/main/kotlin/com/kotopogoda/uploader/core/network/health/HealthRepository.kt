package com.kotopogoda.uploader.core.network.health

import com.kotopogoda.uploader.core.network.api.HealthApi
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.collections.firstNotNullOfOrNull

@Singleton
class HealthRepository @Inject constructor(
    private val networkClientProvider: NetworkClientProvider,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun check(): Result = withContext(ioDispatcher) {
        val start = clock.instant()
        val api = networkClientProvider.create(HealthApi::class.java)
        val callResult = runCatching { api.health() }
        val end = clock.instant()
        val latencyMillis = durationMillisBetween(start, end)

        callResult.fold(
            onSuccess = { response ->
                val status = parseStatus(response.status)
                Result(
                    status = status,
                    checkedAt = end,
                    latencyMillis = latencyMillis,
                    message = response.message ?: status.messageOrNull(response.status),
                )
            },
            onFailure = { error ->
                Result(
                    status = HealthStatus.OFFLINE,
                    checkedAt = end,
                    latencyMillis = latencyMillis,
                    message = error.message,
                )
            },
        )
    }

    private fun parseStatus(rawStatus: Any?): HealthStatus {
        return when (val value = extractStatusValue(rawStatus)) {
            is String -> value.toHealthStatus()
            is Number -> value.toHealthStatus()
            else -> HealthStatus.UNKNOWN
        }
    }

    private fun extractStatusValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String, is Number -> value
            is Map<*, *> -> {
                val prioritized = value.entries.firstNotNullOfOrNull { (key, entryValue) ->
                    val keyString = key?.toString()?.lowercase()
                    if (keyString == "status" || keyString == "state") {
                        extractStatusValue(entryValue)
                    } else {
                        null
                    }
                }
                prioritized ?: value.entries.firstNotNullOfOrNull { extractStatusValue(it.value) }
            }
            is Iterable<*> -> value.firstNotNullOfOrNull { extractStatusValue(it) }
            is Array<*> -> value.firstNotNullOfOrNull { extractStatusValue(it) }
            else -> null
        }
    }

    private fun String.toHealthStatus(): HealthStatus {
        return when (lowercase().trim()) {
            "online", "healthy", "ok", "success" -> HealthStatus.ONLINE
            "degraded", "warn", "warning", "partial" -> HealthStatus.DEGRADED
            "offline", "down", "error", "fail", "failed" -> HealthStatus.OFFLINE
            else -> HealthStatus.UNKNOWN
        }
    }

    private fun Number.toHealthStatus(): HealthStatus {
        val asInt = toInt()
        return when (asInt) {
            1 -> HealthStatus.ONLINE
            2 -> HealthStatus.DEGRADED
            0 -> HealthStatus.OFFLINE
            else -> HealthStatus.UNKNOWN
        }
    }

    private fun durationMillisBetween(start: Instant, end: Instant): Long {
        return runCatching { Duration.between(start, end).toMillis() }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
    }

    private fun HealthStatus.messageOrNull(rawStatus: Any?): String? {
        if (this != HealthStatus.UNKNOWN) {
            return null
        }
        return rawStatus?.toString()
    }

    data class Result(
        val status: HealthStatus,
        val checkedAt: Instant,
        val latencyMillis: Long,
        val message: String?,
    )
}

private fun <T> Array<T>.firstNotNullOfOrNull(transform: (T) -> Any?): Any? {
    for (element in this) {
        val result = transform(element)
        if (result != null) {
            return result
        }
    }
    return null
}
