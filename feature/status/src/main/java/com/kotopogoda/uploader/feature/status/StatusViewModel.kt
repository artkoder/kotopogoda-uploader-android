package com.kotopogoda.uploader.feature.status

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.os.StatFs

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val healthMonitor: HealthMonitor,
    private val deviceCredsStore: DeviceCredsStore,
    private val uploadQueueRepository: UploadQueueRepository,
    private val folderRepository: FolderRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val storageRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1).apply {
        tryEmit(Unit)
    }

    private val _uiState: StateFlow<StatusUiState> = combine(
        healthMonitor.state,
        deviceCredsStore.credsFlow,
        uploadQueueRepository.observeQueue().map { entries -> entries.toSummary() },
        storageStateFlow(),
    ) { health, creds, queueSummary, storage ->
        StatusUiState(
            health = health,
            pairing = creds.toPairingStatus(),
            queue = queueSummary,
            storage = storage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = StatusUiState(
            health = HealthState.Unknown,
            pairing = PairingStatus.Unknown,
            queue = QueueSummary.Empty,
            storage = StorageStatus.Loading,
        )
    )

    val uiState: StateFlow<StatusUiState> = _uiState

    private val _events = Channel<StatusEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        healthMonitor.start(viewModelScope)
    }

    fun onRefreshHealth() {
        viewModelScope.launch {
            val result = runCatching { healthMonitor.checkOnce() }
            val event = result.fold(
                onSuccess = { state ->
                    StatusEvent.HealthPingResult(
                        isSuccess = true,
                        latencyMillis = state.latencyMillis,
                        error = null,
                    )
                },
                onFailure = { error ->
                    StatusEvent.HealthPingResult(
                        isSuccess = false,
                        latencyMillis = null,
                        error = error,
                    )
                }
            )
            _events.send(event)
        }
    }

    fun onOpenQueue() {
        emitEvent(StatusEvent.OpenQueue)
    }

    fun onOpenPairingSettings() {
        emitEvent(StatusEvent.OpenPairingSettings)
    }

    fun onRequestFolderCheck() {
        val current = uiState.value.storage
        val treeUri = (current as? StorageStatus.Available)?.treeUri
            ?: (current as? StorageStatus.PermissionMissing)?.treeUri
        emitEvent(StatusEvent.RequestFolderAccess(treeUri))
    }

    fun onStorageRefresh() {
        storageRefresh.tryEmit(Unit)
    }

    private fun storageStateFlow(): Flow<StorageStatus> {
        return combine(folderRepository.observeFolder(), storageRefresh) { folder, _ -> folder }
            .flatMapLatest { folder ->
                flow {
                    emit(StorageStatus.Loading)
                    emit(resolveStorage(folder))
                }
            }
            .distinctUntilChanged()
    }

    private suspend fun resolveStorage(folder: Folder?): StorageStatus {
        if (folder == null) {
            return StorageStatus.NotSelected
        }
        val treeUri = runCatching { Uri.parse(folder.treeUri) }.getOrNull()
            ?: return StorageStatus.Error
        val requiredFlags = maskPersistableFlags(folder.flags)
        val hasPersistedPermission = hasPersistedPermission(
            context.contentResolver.persistedUriPermissions,
            treeUri,
            requiredFlags
        )
        val documentFile = DocumentFile.fromTreeUri(context, treeUri)
        val displayName = documentFile?.name ?: treeUri.toString()

        if (!hasPersistedPermission) {
            return StorageStatus.PermissionMissing(treeUri = treeUri, displayName = displayName)
        }

        val stats = runCatching { statFsFor(treeUri) }.getOrNull()
            ?: return StorageStatus.Error

        val freeBytes = stats.availableBytes
        val totalBytes = stats.totalBytes

        return StorageStatus.Available(
            treeUri = treeUri,
            displayName = displayName,
            freeBytes = freeBytes,
            totalBytes = totalBytes,
        )
    }

    private fun statFsFor(treeUri: Uri): StatFs {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val path = when {
            documentId.startsWith("primary:") -> {
                val relativePath = documentId.removePrefix("primary:")
                val base = Environment.getExternalStorageDirectory().absolutePath
                if (relativePath.isBlank()) {
                    base
                } else {
                    val normalized = relativePath.trim('/')
                    if (normalized.isBlank()) base else "$base/$normalized"
                }
            }
            else -> throw IllegalArgumentException("Unsupported volume: $documentId")
        }
        return StatFs(path)
    }

    private fun List<UploadQueueEntry>.toSummary(): QueueSummary {
        if (isEmpty()) {
            return QueueSummary.Empty
        }
        var queued = 0
        var processing = 0
        var succeeded = 0
        var failed = 0
        for (entry in this) {
            when (entry.state) {
                UploadItemState.QUEUED -> queued += 1
                UploadItemState.PROCESSING -> processing += 1
                UploadItemState.SUCCEEDED -> succeeded += 1
                UploadItemState.FAILED -> failed += 1
            }
        }
        val total = queued + processing + succeeded + failed
        return QueueSummary(
            total = total,
            processing = processing,
            queued = queued,
            succeeded = succeeded,
            failed = failed,
        )
    }

    private fun DeviceCreds?.toPairingStatus(): PairingStatus {
        return if (this == null) {
            PairingStatus.Unpaired
        } else {
            val suffix = if (deviceId.length >= 4) deviceId.takeLast(4) else deviceId
            PairingStatus.Paired(deviceIdMask = "••••$suffix", rawDeviceId = deviceId)
        }
    }

    private fun emitEvent(event: StatusEvent) {
        viewModelScope.launch {
            _events.send(event)
        }
    }

    private fun maskPersistableFlags(flags: Int): Int {
        val mask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return flags and mask
    }

    private fun hasPersistedPermission(
        permissions: List<UriPermission>,
        uri: Uri,
        requiredFlags: Int
    ): Boolean {
        if (requiredFlags == 0) {
            return false
        }
        val persisted = permissions.firstOrNull { it.uri == uri } ?: return false
        val persistedFlags =
            (if (persisted.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (persisted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        return persistedFlags and requiredFlags == requiredFlags
    }
}

sealed interface StatusEvent {
    data object OpenQueue : StatusEvent
    data object OpenPairingSettings : StatusEvent
    data class RequestFolderAccess(val treeUri: Uri?) : StatusEvent
    data class HealthPingResult(
        val isSuccess: Boolean,
        val latencyMillis: Long?,
        val error: Throwable?,
    ) : StatusEvent
}

data class StatusUiState(
    val health: HealthState,
    val pairing: PairingStatus,
    val queue: QueueSummary,
    val storage: StorageStatus,
)

sealed interface PairingStatus {
    data object Unknown : PairingStatus
    data object Unpaired : PairingStatus
    data class Paired(val deviceIdMask: String, val rawDeviceId: String) : PairingStatus
}

data class QueueSummary(
    val total: Int,
    val processing: Int,
    val queued: Int,
    val succeeded: Int,
    val failed: Int,
) {
    companion object {
        val Empty = QueueSummary(0, 0, 0, 0, 0)
    }
}

sealed interface StorageStatus {
    data object Loading : StorageStatus
    data object NotSelected : StorageStatus
    data object Error : StorageStatus
    data class PermissionMissing(val treeUri: Uri, val displayName: String) : StorageStatus
    data class Available(
        val treeUri: Uri,
        val displayName: String,
        val freeBytes: Long,
        val totalBytes: Long,
    ) : StorageStatus
}
