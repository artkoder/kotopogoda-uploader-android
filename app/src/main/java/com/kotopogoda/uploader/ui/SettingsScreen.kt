package com.kotopogoda.uploader.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kotopogoda.uploader.R
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onResetPairing: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is SettingsEvent.ShowMessageRes -> {
                    snackbarHostState.showSnackbar(message = context.getString(event.resId))
                }
                is SettingsEvent.ShowLogsExported -> {
                    val message = context.getString(
                        R.string.settings_snackbar_logs_exported,
                        event.path,
                    )
                    val actionLabel = context.getString(R.string.settings_logs_open)
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = actionLabel,
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(event.uri, "application/zip")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val packageManager = context.packageManager
                        val canHandle = packageManager?.let { intent.resolveActivity(it) } != null
                        if (canHandle) {
                            runCatching { context.startActivity(intent) }
                                .onFailure {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.settings_snackbar_open_logs_failed)
                                    )
                                }
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_snackbar_open_logs_failed)
                            )
                        }
                    }
                }
                SettingsEvent.ResetPairing -> {
                    onResetPairing()
                    snackbarHostState.showSnackbar(context.getString(R.string.settings_snackbar_reset_done))
                }
                is SettingsEvent.OpenDocs -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                    context.startActivity(intent)
                }
                SettingsEvent.RequestNotificationPermission -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.onNotificationPermissionResult(true)
                    }
                }
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onBaseUrlChanged = viewModel::onBaseUrlChanged,
        onApplyBaseUrl = viewModel::onApplyBaseUrl,
        onAppLoggingChanged = viewModel::onAppLoggingChanged,
        onHttpLoggingChanged = viewModel::onHttpLoggingChanged,
        onQueueNotificationChanged = viewModel::onQueueNotificationChanged,
        onRequestQueueNotificationPermission = viewModel::onRequestQueueNotificationPermission,
        onPreviewQualityChanged = viewModel::onPreviewQualityChanged,
        onExportLogs = viewModel::onExportLogs,
        onClearQueue = viewModel::onClearQueue,
        onResetPairing = viewModel::onResetPairingConfirmed,
        onOpenDocs = viewModel::onOpenDocs,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onApplyBaseUrl: () -> Unit,
    onAppLoggingChanged: (Boolean) -> Unit,
    onHttpLoggingChanged: (Boolean) -> Unit,
    onQueueNotificationChanged: (Boolean) -> Unit,
    onRequestQueueNotificationPermission: () -> Unit,
    onPreviewQualityChanged: (com.kotopogoda.uploader.core.settings.PreviewQuality) -> Unit,
    onExportLogs: () -> Unit,
    onClearQueue: () -> Unit,
    onResetPairing: () -> Unit,
    onOpenDocs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(text = stringResource(id = R.string.settings_reset_title)) },
            text = { Text(text = stringResource(id = R.string.settings_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetPairing()
                    }
                ) {
                    Text(text = stringResource(id = R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(text = stringResource(id = R.string.settings_reset_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(id = R.string.settings_back)
                        )
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsSectionTitle(text = stringResource(id = R.string.settings_section_connection))
                    OutlinedTextField(
                        value = uiState.baseUrlInput,
                        onValueChange = onBaseUrlChanged,
                        label = { Text(text = stringResource(id = R.string.settings_base_url_label)) },
                        isError = !uiState.isBaseUrlValid,
                        supportingText = {
                            if (!uiState.isBaseUrlValid) {
                                Text(text = stringResource(id = R.string.settings_base_url_error))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onApplyBaseUrl,
                            enabled = uiState.isBaseUrlDirty,
                        ) {
                            Text(text = stringResource(id = R.string.settings_base_url_save))
                        }
                    }
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsSectionTitle(text = stringResource(id = R.string.settings_section_enhancement))
                    SettingsSwitchRow(
                        title = stringResource(id = R.string.settings_preview_quality),
                        description = stringResource(id = R.string.settings_preview_quality_desc),
                        checked = uiState.previewQuality == com.kotopogoda.uploader.core.settings.PreviewQuality.QUALITY,
                        onCheckedChange = { isQuality ->
                            val quality = if (isQuality) {
                                com.kotopogoda.uploader.core.settings.PreviewQuality.QUALITY
                            } else {
                                com.kotopogoda.uploader.core.settings.PreviewQuality.BALANCED
                            }
                            onPreviewQualityChanged(quality)
                        },
                    )
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsSectionTitle(text = stringResource(id = R.string.settings_section_logging))
                    SettingsSwitchRow(
                        title = stringResource(id = R.string.settings_app_logging),
                        description = stringResource(id = R.string.settings_app_logging_desc),
                        checked = uiState.appLoggingEnabled,
                        onCheckedChange = onAppLoggingChanged,
                    )
                    SettingsSwitchRow(
                        title = stringResource(id = R.string.settings_http_logging),
                        description = stringResource(id = R.string.settings_http_logging_desc),
                        checked = uiState.httpLoggingEnabled,
                        onCheckedChange = onHttpLoggingChanged,
                    )
                    SettingsSwitchRow(
                        title = stringResource(id = R.string.settings_queue_notification),
                        description = stringResource(id = R.string.settings_queue_notification_desc),
                        checked = uiState.queueNotificationPersistent,
                        onCheckedChange = onQueueNotificationChanged,
                        enabled = uiState.isQueueNotificationToggleEnabled,
                    )
                    if (!uiState.queueNotificationPermissionGranted) {
                        Text(
                            text = stringResource(id = R.string.settings_queue_notification_permission_rationale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onRequestQueueNotificationPermission,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(id = R.string.settings_queue_notification_permission_button))
                        }
                    }
                    Button(
                        onClick = onExportLogs,
                        enabled = !uiState.isExporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.settings_export_logs))
                    }
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsSectionTitle(text = stringResource(id = R.string.settings_section_actions))
                    Button(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.settings_reset_button))
                    }
                    OutlinedButton(
                        onClick = onClearQueue,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.settings_clear_queue))
                    }
                }
            }

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsSectionTitle(text = stringResource(id = R.string.settings_section_about))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.settings_version_app, uiState.appVersion),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(id = R.string.settings_version_contract, uiState.contractVersion),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(
                                    id = R.string.settings_logs_directory,
                                    uiState.logsDirectoryPath,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onOpenDocs) {
                                Text(text = stringResource(id = R.string.settings_open_docs))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
