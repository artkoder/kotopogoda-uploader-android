@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.kotopogoda.uploader.feature.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus
import com.kotopogoda.uploader.feature.viewer.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun ViewerRoute(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    healthState: HealthState,
    viewModel: ViewerViewModel = hiltViewModel()
) {
    val photos = viewModel.photos.collectAsLazyPagingItems()
    val pagerScrollEnabled by viewModel.isPagerScrollEnabled.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val undoCount by viewModel.undoCount.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val actionInProgress by viewModel.actionInProgress.collectAsState()
    val currentFolderUri by viewModel.currentFolderTreeUri.collectAsState()
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val resultFlags = result.data?.flags ?: 0
            val mask = maskPersistableFlags(resultFlags)
            try {
                if (mask != 0) {
                    contentResolver.takePersistableUriPermission(uri, mask)
                }
                currentFolderUri?.let { previousUriString ->
                    val previousUri = Uri.parse(previousUriString)
                    contentResolver.persistedUriPermissions
                        .firstOrNull { it.uri == previousUri }
                        ?.let { persistedPermission ->
                            val releaseFlags =
                                (if (persistedPermission.isReadPermission) {
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                } else {
                                    0
                                }) or
                                    (if (persistedPermission.isWritePermission) {
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    } else {
                                        0
                                    })
                            runCatching {
                                contentResolver.releasePersistableUriPermission(
                                    persistedPermission.uri,
                                    releaseFlags
                                )
                            }
                        }
                }
                viewModel.onFolderSelected(uri.toString(), mask)
            } catch (_: SecurityException) {
                // ignore and leave the state unchanged
            }
        }
    }

    val launchFolderPicker = remember(folderPickerLauncher) {
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
            folderPickerLauncher.launch(intent)
        }
    }

    ViewerScreen(
        photos = photos,
        currentIndex = currentIndex,
        isPagerScrollEnabled = pagerScrollEnabled,
        undoCount = undoCount,
        canUndo = canUndo,
        actionInProgress = actionInProgress,
        events = viewModel.events,
        observeUploadEnqueued = viewModel::observeUploadEnqueued,
        onBack = onBack,
        onOpenQueue = onOpenQueue,
        onOpenStatus = onOpenStatus,
        onOpenSettings = onOpenSettings,
        healthState = healthState,
        onPageChanged = viewModel::setCurrentIndex,
        onVisiblePhotoChanged = viewModel::updateVisiblePhoto,
        onZoomStateChanged = { atBase -> viewModel.setPagerScrollEnabled(atBase) },
        onSkip = viewModel::onSkip,
        onMoveToProcessing = viewModel::onMoveToProcessing,
        onEnqueueUpload = viewModel::onEnqueueUpload,
        onUndo = viewModel::onUndo,
        onJumpToDate = viewModel::jumpToDate,
        onSelectFolder = {
            launchFolderPicker(currentFolderUri?.let(Uri::parse))
        }
    )
}

private fun maskPersistableFlags(flags: Int): Int {
    val mask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    return flags and mask
}

@VisibleForTesting
@Composable
internal fun ViewerScreen(
    photos: LazyPagingItems<PhotoItem>,
    currentIndex: Int,
    isPagerScrollEnabled: Boolean,
    undoCount: Int,
    canUndo: Boolean,
    actionInProgress: ViewerViewModel.ViewerActionInProgress?,
    events: Flow<ViewerViewModel.ViewerEvent>,
    observeUploadEnqueued: (Uri) -> Flow<Boolean>,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    healthState: HealthState,
    onPageChanged: (Int) -> Unit,
    onVisiblePhotoChanged: (Int, PhotoItem?) -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    onSkip: (PhotoItem?) -> Unit,
    onMoveToProcessing: (PhotoItem?) -> Unit,
    onEnqueueUpload: (PhotoItem?) -> Unit,
    onUndo: () -> Unit,
    onJumpToDate: (Instant) -> Unit,
    onSelectFolder: () -> Unit
) {
    BackHandler(onBack = onBack)

    val itemCount = photos.itemCount
    val isRefreshing = photos.loadState.refresh is LoadState.Loading
    val refreshError = photos.loadState.refresh as? LoadState.Error

    if (itemCount == 0) {
        LaunchedEffect(itemCount) {
            onVisiblePhotoChanged(0, null)
        }
        if (isRefreshing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (refreshError != null) {
            ViewerErrorState(
                message = refreshError.error.message,
                onSelectFolder = onSelectFolder,
                onBack = onBack
            )
        } else {
            ViewerEmptyState()
        }
        return
    }

    val clampedIndex = currentIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    var rememberedIndex by rememberSaveable(currentIndex, itemCount) { mutableStateOf(clampedIndex) }
    val pagerState = rememberPagerState(initialPage = rememberedIndex, pageCount = { itemCount })
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showJumpSheet by rememberSaveable { mutableStateOf(false) }
    val jumpSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentPhoto = if (rememberedIndex in 0 until itemCount) {
        photos[rememberedIndex]
    } else {
        null
    }
    val isQueuedFlow = remember(currentPhoto?.uri) {
        currentPhoto?.let { observeUploadEnqueued(it.uri) } ?: flowOf(false)
    }
    val isCurrentQueued by isQueuedFlow.collectAsState(initial = false)
    val isBusy = actionInProgress != null

    if (showJumpSheet) {
        ModalBottomSheet(
            onDismissRequest = { showJumpSheet = false },
            sheetState = jumpSheetState
        ) {
            JumpToDateSheet(
                initialDate = currentPhoto?.takenAt,
                onJump = { target ->
                    onJumpToDate(target)
                    coroutineScope.launch { jumpSheetState.hide() }
                        .invokeOnCompletion { showJumpSheet = false }
                },
                onDismiss = {
                    coroutineScope.launch { jumpSheetState.hide() }
                        .invokeOnCompletion { showJumpSheet = false }
                }
            )
        }
    }

    LaunchedEffect(currentIndex, itemCount) {
        val clamped = currentIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
        if (clamped != rememberedIndex) {
            rememberedIndex = clamped
        }
        if (itemCount > 0 && pagerState.currentPage != clamped) {
            pagerState.scrollToPage(clamped)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collectLatest { page ->
                if (rememberedIndex != page) {
                    rememberedIndex = page
                }
                onPageChanged(page)
            }
    }

    LaunchedEffect(itemCount, rememberedIndex, currentPhoto?.id, currentPhoto?.takenAt) {
        onVisiblePhotoChanged(itemCount, currentPhoto)
    }

    val context = LocalContext.current

    LaunchedEffect(events, context) {
        events.collectLatest { event ->
            when (event) {
                is ViewerViewModel.ViewerEvent.ShowSnackbar -> {
                    val message = context.getString(event.messageRes)
                    val actionLabel = if (event.withUndo) {
                        context.getString(R.string.viewer_snackbar_action_undo)
                    } else {
                        null
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = actionLabel
                    )
                    if (result == SnackbarResult.ActionPerformed && event.withUndo) {
                        onUndo()
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            ViewerTopBar(
                onBack = onBack,
                onOpenQueue = onOpenQueue,
                onOpenStatus = onOpenStatus,
                onOpenSettings = onOpenSettings,
                onOpenJumpToDate = { showJumpSheet = true },
                healthState = healthState,
            )
        },
        bottomBar = {
            ViewerActionBar(
                skipEnabled = !isBusy && currentIndex < itemCount - 1,
                processingEnabled = !isBusy && currentPhoto != null,
                publishEnabled = !isBusy && currentPhoto != null && !isCurrentQueued,
                processingBusy = actionInProgress == ViewerViewModel.ViewerActionInProgress.Processing,
                publishBusy = actionInProgress == ViewerViewModel.ViewerActionInProgress.Upload,
                canUndo = canUndo && !isBusy,
                undoCount = undoCount,
                onSkip = { onSkip(currentPhoto) },
                onMoveToProcessing = { onMoveToProcessing(currentPhoto) },
                onEnqueueUpload = { onEnqueueUpload(currentPhoto) },
                onUndo = onUndo
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            VerticalPager(
                state = pagerState,
                userScrollEnabled = isPagerScrollEnabled && !isBusy,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = photos[page]
                Box(modifier = Modifier.fillMaxSize()) {
                    if (item != null) {
                        ZoomableImage(
                            uri = item.uri,
                            modifier = Modifier.fillMaxSize(),
                            onZoomChanged = onZoomStateChanged
                        )
                        if (page == currentIndex && isCurrentQueued) {
                            UploadQueuedBadge(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(16.dp)
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenJumpToDate: () -> Unit,
    onOpenSettings: () -> Unit,
    healthState: HealthState,
) {
    SmallTopAppBar(
        title = {
            HealthStatusBadge(healthState = healthState)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = stringResource(id = R.string.viewer_back)
                )
            }
        },
        actions = {
            IconButton(onClick = onOpenJumpToDate) {
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = stringResource(id = R.string.viewer_open_calendar)
                )
            }
            IconButton(onClick = onOpenStatus) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = stringResource(id = R.string.viewer_open_status)
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = stringResource(id = R.string.viewer_open_queue)
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(id = R.string.viewer_open_settings)
                )
            }
        },
        colors = TopAppBarDefaults.smallTopAppBarColors()
    )
}

@Composable
private fun JumpToDateSheet(
    initialDate: Instant?,
    onJump: (Instant) -> Unit,
    onDismiss: () -> Unit
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val initialLocalDate = remember(initialDate, zoneId) {
        initialDate?.atZone(zoneId)?.toLocalDate() ?: LocalDate.now(zoneId)
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialLocalDate.startOfDayInstant(zoneId).toEpochMilli()
    )
    var jumpMode by rememberSaveable { mutableStateOf(JumpMode.DATE) }
    val presets = remember(zoneId) {
        val today = LocalDate.now(zoneId)
        listOf(
            R.string.viewer_jump_today to today,
            R.string.viewer_jump_minus_week to today.minusWeeks(1),
            R.string.viewer_jump_minus_month to today.minusMonths(1)
        )
    }
    val canConfirm = datePickerState.selectedDateMillis != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.viewer_jump_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Start
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilterChip(
                selected = jumpMode == JumpMode.DATE,
                onClick = { jumpMode = JumpMode.DATE },
                label = { Text(text = stringResource(id = R.string.viewer_jump_mode_date)) }
            )
            FilterChip(
                selected = jumpMode == JumpMode.MONTH,
                onClick = { jumpMode = JumpMode.MONTH },
                label = { Text(text = stringResource(id = R.string.viewer_jump_mode_month)) }
            )
        }
        DatePicker(state = datePickerState)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            presets.forEach { (labelRes, date) ->
                AssistChip(
                    onClick = {
                        datePickerState.selectedDateMillis = date.startOfDayInstant(zoneId).toEpochMilli()
                    },
                    label = { Text(text = stringResource(id = labelRes)) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
            Button(
                onClick = {
                    val selectedMillis = datePickerState.selectedDateMillis ?: return@Button
                    val selectedDate = Instant.ofEpochMilli(selectedMillis).atZone(zoneId).toLocalDate()
                    val targetInstant = when (jumpMode) {
                        JumpMode.DATE -> selectedDate.startOfDayInstant(zoneId)
                        JumpMode.MONTH -> selectedDate.withDayOfMonth(1).startOfDayInstant(zoneId)
                    }
                    onJump(targetInstant)
                },
                enabled = canConfirm,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(id = R.string.viewer_jump_confirm))
            }
        }
    }
}

private enum class JumpMode { DATE, MONTH }

private fun LocalDate.startOfDayInstant(zoneId: ZoneId): Instant =
    atStartOfDay(zoneId).toInstant()

@Composable
private fun HealthStatusBadge(
    healthState: HealthState,
    modifier: Modifier = Modifier,
) {
    val (labelRes, color) = when (healthState.status) {
        HealthStatus.ONLINE -> R.string.viewer_health_online to MaterialTheme.colorScheme.tertiary
        HealthStatus.DEGRADED -> R.string.viewer_health_degraded to MaterialTheme.colorScheme.secondary
        HealthStatus.OFFLINE -> R.string.viewer_health_offline to MaterialTheme.colorScheme.error
        HealthStatus.UNKNOWN -> R.string.viewer_health_unknown to MaterialTheme.colorScheme.outline
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

@Composable
private fun ViewerActionBar(
    skipEnabled: Boolean,
    processingEnabled: Boolean,
    publishEnabled: Boolean,
    processingBusy: Boolean,
    publishBusy: Boolean,
    canUndo: Boolean,
    undoCount: Int,
    onSkip: () -> Unit,
    onMoveToProcessing: () -> Unit,
    onEnqueueUpload: () -> Unit,
    onUndo: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val buttonModifier = Modifier
                .weight(1f)
                .height(44.dp)
            val skipColors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val processingColors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
            val publishColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            FilledTonalButton(
                onClick = onSkip,
                enabled = skipEnabled,
                modifier = buttonModifier,
                colors = skipColors
            ) {
                ActionButtonContent(text = stringResource(id = R.string.viewer_action_skip))
            }
            FilledTonalButton(
                onClick = onMoveToProcessing,
                enabled = processingEnabled,
                modifier = buttonModifier,
                colors = processingColors
            ) {
                ActionButtonContent(
                    text = stringResource(id = R.string.viewer_action_processing),
                    busy = processingBusy
                )
            }
            Button(
                onClick = onEnqueueUpload,
                enabled = publishEnabled,
                modifier = buttonModifier,
                colors = publishColors
            ) {
                ActionButtonContent(
                    text = stringResource(id = R.string.viewer_action_publish),
                    busy = publishBusy
                )
            }
            val undoTooltipText = stringResource(id = R.string.viewer_action_undo_tooltip)
            val undoContentDescription = stringResource(id = R.string.viewer_action_undo)
            OutlinedButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = buttonModifier
                    .semantics {
                        contentDescription = undoContentDescription
                        if (!canUndo) {
                            stateDescription = undoTooltipText
                        }
                    }
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = undoContentDescription
                        )
                        ActionButtonLabel(text = stringResource(id = R.string.viewer_action_undo))
                        if (undoCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    text = undoCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtonContent(
    text: String,
    busy: Boolean = false
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        ActionButtonLabel(text = text)
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(18.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun ActionButtonLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ViewerActionBarPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        ViewerActionBar(
            skipEnabled = true,
            processingEnabled = true,
            publishEnabled = true,
            processingBusy = true,
            publishBusy = false,
            canUndo = true,
            undoCount = 3,
            onSkip = {},
            onMoveToProcessing = {},
            onEnqueueUpload = {},
            onUndo = {}
        )
    }
}

@Composable
private fun UploadQueuedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 6.dp
    ) {
        Text(
            text = stringResource(id = R.string.viewer_badge_enqueued),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ViewerErrorState(
    message: String?,
    onSelectFolder: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.viewer_error_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = message ?: stringResource(id = R.string.viewer_error_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onSelectFolder) {
            Text(text = stringResource(id = R.string.viewer_error_select_again))
        }
        OutlinedButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.viewer_error_back))
        }
        Text(
            text = stringResource(id = R.string.viewer_error_sd_hint),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ViewerEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.viewer_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(id = R.string.viewer_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}
