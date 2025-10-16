package com.kotopogoda.uploader.feature.status

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus
import com.kotopogoda.uploader.feature.status.R
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusRoute(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPairingSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val folderPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val mask = maskPersistableFlags(result.data?.flags ?: 0)
            if (mask != 0) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, mask)
                }
            }
            viewModel.onStorageRefresh()
        }
    }

    val launchFolderPicker = remember(folderPermissionLauncher) {
        { initialUri: Uri? ->
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (initialUri != null) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            }
            folderPermissionLauncher.launch(intent)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                StatusEvent.OpenQueue -> onOpenQueue()
                StatusEvent.OpenPairingSettings -> onOpenPairingSettings()
                is StatusEvent.RequestFolderAccess -> {
                    launchFolderPicker(event.treeUri)
                }
            }
        }
    }

    StatusScreen(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRefreshHealth = viewModel::onRefreshHealth,
        onOpenQueue = viewModel::onOpenQueue,
        onOpenPairingSettings = viewModel::onOpenPairingSettings,
        onCheckFolderAccess = viewModel::onRequestFolderCheck,
        modifier = modifier,
    )
}

private fun maskPersistableFlags(flags: Int): Int {
    val mask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return flags and mask
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    state: StatusUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRefreshHealth: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPairingSettings: () -> Unit,
    onCheckFolderAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.status_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = stringResource(id = R.string.status_back))
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshHealth) {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = stringResource(id = R.string.status_refresh_health))
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                HealthCard(state = state, onRefresh = onRefreshHealth)
            }
            item {
                PairingCard(pairing = state.pairing, onOpenPairingSettings = onOpenPairingSettings)
            }
            item {
                QueueCard(summary = state.queue, onOpenQueue = onOpenQueue)
            }
            item {
                StorageCard(storage = state.storage, onCheckFolderAccess = onCheckFolderAccess)
            }
        }
    }
}

@Composable
private fun HealthCard(state: StatusUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(id = R.string.status_section_server), style = MaterialTheme.typography.titleMedium)
            val statusText = when (state.health.status) {
                HealthStatus.ONLINE -> R.string.status_health_online
                HealthStatus.DEGRADED -> R.string.status_health_degraded
                HealthStatus.OFFLINE -> R.string.status_health_offline
                HealthStatus.UNKNOWN -> R.string.status_health_unknown
            }
            Text(text = stringResource(id = statusText), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            state.health.message?.takeIf { it.isNotBlank() }?.let { message ->
                val displayMessage = when (message) {
                    HealthState.MESSAGE_PARSE_ERROR -> stringResource(id = R.string.status_health_parse_error)
                    else -> message
                }
                Text(text = displayMessage, style = MaterialTheme.typography.bodyMedium)
            }
            val lastChecked = state.health.lastCheckedAt
            val latency = state.health.latencyMillis
            if (lastChecked != null) {
                val formattedTime = formatter.format(lastChecked)
                val text = if (latency != null) {
                    val latencyText = stringResource(id = R.string.status_health_latency_ms, latency)
                    stringResource(
                        id = R.string.status_health_checked_at_with_duration,
                        formattedTime,
                        latencyText
                    )
                } else {
                    stringResource(id = R.string.status_health_checked_at, formattedTime)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (latency != null) {
                Text(
                    text = stringResource(id = R.string.status_health_latency_ms, latency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRefresh) {
                Text(text = stringResource(id = R.string.status_health_ping))
            }
        }
    }
}

@Composable
private fun PairingCard(pairing: PairingStatus, onOpenPairingSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(id = R.string.status_section_pairing), style = MaterialTheme.typography.titleMedium)
            when (pairing) {
                PairingStatus.Unknown -> {
                    Text(text = stringResource(id = R.string.status_pairing_unknown), style = MaterialTheme.typography.bodyMedium)
                }
                PairingStatus.Unpaired -> {
                    Text(text = stringResource(id = R.string.status_pairing_unpaired), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenPairingSettings) {
                        Text(text = stringResource(id = R.string.status_pairing_open_settings))
                    }
                }
                is PairingStatus.Paired -> {
                    Text(text = stringResource(id = R.string.status_pairing_id, pairing.deviceIdMask), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenPairingSettings) {
                        Text(text = stringResource(id = R.string.status_pairing_reset))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueCard(summary: QueueSummary, onOpenQueue: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(id = R.string.status_section_queue), style = MaterialTheme.typography.titleMedium)
            if (summary.total == 0) {
                Text(text = stringResource(id = R.string.status_queue_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(text = stringResource(id = R.string.status_queue_running, summary.processing, summary.total), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(id = R.string.status_queue_enqueued, summary.queued), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(id = R.string.status_queue_failed, summary.failed), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onOpenQueue) {
                Text(text = stringResource(id = R.string.status_queue_open))
            }
        }
    }
}

@Composable
private fun StorageCard(storage: StorageStatus, onCheckFolderAccess: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(id = R.string.status_section_storage), style = MaterialTheme.typography.titleMedium)
            when (storage) {
                StorageStatus.Loading -> {
                    Text(text = stringResource(id = R.string.status_storage_loading), style = MaterialTheme.typography.bodyMedium)
                }
                StorageStatus.NotSelected -> {
                    Text(text = stringResource(id = R.string.status_storage_not_selected), style = MaterialTheme.typography.bodyMedium)
                }
                StorageStatus.Error -> {
                    Text(text = stringResource(id = R.string.status_storage_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onCheckFolderAccess) {
                        Text(text = stringResource(id = R.string.status_storage_check_access))
                    }
                }
                is StorageStatus.PermissionMissing -> {
                    Text(text = stringResource(id = R.string.status_storage_permission_missing, storage.displayName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onCheckFolderAccess) {
                        Text(text = stringResource(id = R.string.status_storage_request_permission))
                    }
                }
                is StorageStatus.Available -> {
                    Text(text = storage.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    val free = formatBytes(storage.freeBytes)
                    val total = formatBytes(storage.totalBytes)
                    Text(text = stringResource(id = R.string.status_storage_space, free, total), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onCheckFolderAccess) {
                        Text(text = stringResource(id = R.string.status_storage_check_access))
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kilo = 1024.0
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(kilo)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / Math.pow(kilo, digitGroups.toDouble())
    return "${"%.1f".format(value)} ${units[digitGroups]}"
}
