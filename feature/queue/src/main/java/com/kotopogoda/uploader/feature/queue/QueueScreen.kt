package com.kotopogoda.uploader.feature.queue

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.feature.queue.R

const val QUEUE_ROUTE = "queue"

@Composable
fun QueueRoute(
    onBack: () -> Unit,
    healthState: HealthState,
    isNetworkValidated: Boolean,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.ensureSummaryRunning()
        viewModel.startUploadProcessing()
    }
    QueueScreen(
        state = state,
        healthState = healthState,
        isNetworkValidated = isNetworkValidated,
        onBack = onBack,
        onCancel = viewModel::onCancel,
        onRetry = viewModel::onRetry
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: QueueUiState,
    healthState: HealthState,
    isNetworkValidated: Boolean,
    onBack: () -> Unit,
    onCancel: (QueueItemUiModel) -> Unit,
    onRetry: (QueueItemUiModel) -> Unit
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = stringResource(id = R.string.queue_title))
                        HealthStatusBadge(
                            healthState = healthState,
                            isNetworkValidated = isNetworkValidated,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(id = R.string.queue_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.smallTopAppBarColors()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.Top,
        ) {
            if (!isNetworkValidated) {
                OfflineNotice(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            if (state.isEmpty) {
                EmptyQueue(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        QueueItemCard(
                            item = item,
                            onCancel = onCancel,
                            onRetry = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyQueue(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.queue_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OfflineNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = stringResource(id = R.string.queue_offline_notice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun QueueItemCard(
    item: QueueItemUiModel,
    onCancel: (QueueItemUiModel) -> Unit,
    onRetry: (QueueItemUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (item.isIndeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = (item.progressPercent ?: 0) / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val progressLabel = if (item.isIndeterminate) {
                stringResource(id = R.string.queue_progress_unknown)
            } else {
                val totalBytes = item.totalBytes
                val bytesSent = item.bytesSent
                if (totalBytes != null && totalBytes > 0 && bytesSent != null) {
                    val sentLabel = Formatter.formatShortFileSize(context, bytesSent.coerceAtLeast(0))
                    val totalLabel = Formatter.formatShortFileSize(context, totalBytes.coerceAtLeast(0))
                    stringResource(
                        id = R.string.queue_progress_with_total,
                        item.progressPercent ?: 0,
                        sentLabel,
                        totalLabel
                    )
                } else {
                    stringResource(id = R.string.queue_progress_percent, item.progressPercent ?: 0)
                }
            }
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            val errorMessage = queueItemErrorMessage(context.resources, item)
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = item.statusResId),
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.highlightWarning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            if (item.isActiveTransfer) {
                Text(
                    text = stringResource(id = R.string.queue_transfer_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            item.waitingReasons.forEach { reason ->
                val args = reason.formatArgs.toTypedArray()
                Text(
                    text = stringResource(id = reason.messageResId, *args),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.canCancel) {
                    OutlinedButton(onClick = { onCancel(item) }) {
                        Text(text = stringResource(id = R.string.queue_action_cancel))
                    }
                }
                if (item.canRetry) {
                    TextButton(onClick = { onRetry(item) }) {
                        Text(text = stringResource(id = R.string.queue_action_retry))
                    }
                }
            }
        }
    }
}

internal fun queueItemErrorMessage(resources: Resources, item: QueueItemUiModel): String? {
    val message = item.lastErrorMessage?.takeIf { it.isNotBlank() }
        ?: item.lastErrorKind?.let { kind ->
            when (kind) {
                UploadErrorKind.HTTP -> {
                    val code = item.lastErrorHttpCode
                    if (code != null) {
                        resources.getString(R.string.queue_error_http_with_code, code)
                    } else {
                        resources.getString(R.string.queue_error_http)
                    }
                }
                UploadErrorKind.AUTH -> resources.getString(R.string.queue_error_auth)
                UploadErrorKind.NETWORK -> resources.getString(R.string.queue_error_network)
                UploadErrorKind.IO -> resources.getString(R.string.queue_error_io)
                UploadErrorKind.REMOTE_FAILURE -> resources.getString(R.string.queue_error_remote_failure)
                UploadErrorKind.UNEXPECTED -> resources.getString(R.string.queue_error_unexpected)
            }
        }
    return message?.let { resources.getString(R.string.queue_last_error, it) }
}

@Composable
private fun HealthStatusBadge(
    healthState: HealthState,
    isNetworkValidated: Boolean,
    modifier: Modifier = Modifier,
) {
    val status = if (isNetworkValidated) {
        healthState.status
    } else {
        HealthStatus.OFFLINE
    }
    val (labelRes, color) = when (status) {
        HealthStatus.ONLINE -> R.string.queue_health_online to MaterialTheme.colorScheme.tertiary
        HealthStatus.DEGRADED -> R.string.queue_health_degraded to MaterialTheme.colorScheme.secondary
        HealthStatus.OFFLINE -> R.string.queue_health_offline to MaterialTheme.colorScheme.error
        HealthStatus.UNKNOWN -> R.string.queue_health_unknown to MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = stringResource(id = labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
