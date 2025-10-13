package com.kotopogoda.uploader.feature.pairing.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.feature.pairing.domain.PairingEvent
import com.kotopogoda.uploader.feature.pairing.domain.PairingUiState
import com.kotopogoda.uploader.feature.pairing.domain.PairingViewModel
import kotlinx.coroutines.launch

@Composable
fun PairingRoute(
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.exportLogs()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Нет доступа к памяти — экспорт невозможен")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                PairingEvent.Success -> onPaired()
                is PairingEvent.Error -> coroutineScope.launch {
                    snackbarHostState.showSnackbar(event.message)
                }
                is PairingEvent.Info -> coroutineScope.launch {
                    snackbarHostState.showSnackbar(event.message)
                }
                is PairingEvent.LogsExported -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val onExportLogs = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.exportLogs()
        } else {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    PairingScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onTokenChanged = viewModel::onTokenInputChanged,
        onAttachClick = viewModel::attachWithCurrentInput,
        onTokenDetected = viewModel::attach,
        onParsingError = viewModel::onParsingError,
        onExportLogs = onExportLogs,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    uiState: PairingUiState,
    snackbarHostState: SnackbarHostState,
    onTokenChanged: (String) -> Unit,
    onAttachClick: () -> Unit,
    onTokenDetected: (String) -> Unit,
    onParsingError: (String?) -> Unit,
    onExportLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (selectedTab, onTabSelected) = rememberSaveable { mutableStateOf(PairingTab.Scanner) }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(text = "Привязка устройства")
            Spacer(modifier = Modifier.height(16.dp))
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                PairingTab.values().forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab.ordinal == index,
                        onClick = { onTabSelected(tab) },
                        text = { Text(tab.title) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            when (selectedTab) {
                PairingTab.Scanner -> ScannerTabContent(
                    isLoading = uiState.isLoading,
                    onTokenDetected = onTokenDetected,
                    onParsingError = onParsingError,
                )
                PairingTab.Manual -> ManualTabContent(
                    uiState = uiState,
                    onTokenChanged = onTokenChanged,
                    onAttachClick = onAttachClick,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onExportLogs,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isExportingLogs,
            ) {
                if (uiState.isExportingLogs) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Сохранение...")
                } else {
                    Text(text = "Экспорт логов")
                }
            }
        }
    }
}

@Composable
private fun ScannerTabContent(
    isLoading: Boolean,
    onTokenDetected: (String) -> Unit,
    onParsingError: (String?) -> Unit,
) {
    val hasCameraPermission = rememberCameraPermission()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!hasCameraPermission.value) {
            Text(text = "Предоставьте доступ к камере для сканирования QR-кодов")
        } else {
            QrScanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                onTokenDetected = onTokenDetected,
                onParsingError = onParsingError,
            )
        }
        if (isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ManualTabContent(
    uiState: PairingUiState,
    onTokenChanged: (String) -> Unit,
    onAttachClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(text = "Введите токен из Telegram")
        }
        item {
            TextField(
                value = uiState.tokenInput,
                onValueChange = onTokenChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = "Токен") },
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = onAttachClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(text = "Привязать")
                }
            }
        }
    }
}

private enum class PairingTab(val title: String) {
    Scanner("QR-сканер"),
    Manual("Ввод кода"),
}

@Composable
private fun rememberCameraPermission(): MutableState<Boolean> {
    val context = LocalContext.current
    val permissionGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted.value = granted
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted.value) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    return permissionGranted
}
