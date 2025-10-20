package com.kotopogoda.uploader.core.logging.diagnostics

import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

data class WorkInfoSnapshot(
    val id: String,
    val state: String,
    val tags: Set<String>,
    val runAttemptCount: Int,
    val progress: Map<String, String>,
    val output: Map<String, String>,
)

interface WorkInfoProvider {
    suspend fun getWorkInfosByTag(tag: String): List<WorkInfoSnapshot>
}

@Singleton
class WorkManagerWorkInfoProvider @Inject constructor(
    private val workManagerProvider: Provider<WorkManager>,
) : WorkInfoProvider {
    override suspend fun getWorkInfosByTag(tag: String): List<WorkInfoSnapshot> {
        return runCatching {
            workManagerProvider.get().getWorkInfosByTag(tag).get()
        }.getOrDefault(emptyList()).map { info ->
            WorkInfoSnapshot(
                id = info.id.toString(),
                state = info.state.name,
                tags = info.tags,
                runAttemptCount = info.runAttemptCount,
                progress = info.progress.keyValueMap.toStringMap(),
                output = info.outputData.keyValueMap.toStringMap(),
            )
        }
    }

    private fun Map<String, Any?>.toStringMap(): Map<String, String> {
        if (isEmpty()) return emptyMap()
        return entries.associate { (key, value) ->
            val normalized = when (value) {
                null -> "null"
                is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
                else -> value.toString()
            }
            key to normalized
        }
    }
}
