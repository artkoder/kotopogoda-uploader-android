package com.kotopogoda.uploader.core.network.health

import java.time.Instant

enum class HealthStatus {
    UNKNOWN,
    ONLINE,
    DEGRADED,
    OFFLINE,
}

data class HealthState(
    val status: HealthStatus,
    val lastCheckedAt: Instant? = null,
    val message: String? = null,
    val latencyMillis: Long? = null,
) {
    companion object {
        val Unknown = HealthState(status = HealthStatus.UNKNOWN, lastCheckedAt = null)
    }
}
