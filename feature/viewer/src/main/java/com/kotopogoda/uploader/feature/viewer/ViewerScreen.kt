@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.kotopogoda.uploader.feature.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Badge
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ViewerRoute(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    healthState: HealthState,
    isNetworkValidated: Boolean,
    viewModel: ViewerViewModel = hiltViewModel()
) {
    val photos = viewModel.photos.collectAsLazyPagingItems()
    val pagerScrollEnabled by viewModel.isPagerScrollEnabled.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val undoCount by viewModel.undoCount.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val actionInProgress by viewModel.actionInProgress.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val currentFolderUri by viewModel.currentFolderTreeUri.collectAsState()
    val enhancementState by viewModel.enhancementState.collectAsState()
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
        selection = selection,
        isSelectionMode = isSelectionMode,
        observeUploadEnqueued = viewModel::observeUploadEnqueued,
        onBack = onBack,
        onOpenQueue = onOpenQueue,
        onOpenStatus = onOpenStatus,
        onOpenSettings = onOpenSettings,
        healthState = healthState,
        isNetworkValidated = isNetworkValidated,
        onPageChanged = viewModel::setCurrentIndex,
        onVisiblePhotoChanged = viewModel::updateVisiblePhoto,
        onZoomStateChanged = { atBase -> viewModel.setPagerScrollEnabled(atBase) },
        onSkip = viewModel::onSkip,
        onMoveToProcessing = viewModel::onMoveToProcessing,
        onCancelSelection = viewModel::onCancelSelection,
        onMoveSelection = viewModel::onMoveSelection,
        onEnqueueUpload = viewModel::onEnqueueUpload,
        onUndo = viewModel::onUndo,
        onDelete = viewModel::onDelete,
        onDeleteSelection = viewModel::onDeleteSelection,
        onDeleteResult = viewModel::onDeleteResult,
        onWriteRequestResult = viewModel::onWriteRequestResult,
        onJumpToDate = viewModel::jumpToDate,
        onScrollToNewest = viewModel::scrollToNewest,
        onPhotoLongPress = viewModel::onPhotoLongPress,
        onToggleSelection = viewModel::onToggleSelection,
        onSelectFolder = {
            launchFolderPicker(currentFolderUri?.let(Uri::parse))
        },
        enhancementStrength = enhancementState.strength,
        enhancementInProgress = enhancementState.inProgress,
        enhancementReady = enhancementState.isResultReady,
        enhancementResultUri = enhancementState.resultUri,
        isEnhancementResultForCurrentPhoto = enhancementState.isResultForCurrentPhoto,
        enhancementProgress = enhancementState.progressByTile,
        onEnhancementStrengthChange = viewModel::onEnhancementStrengthChange,
        onEnhancementStrengthChangeFinished = viewModel::onEnhancementStrengthChangeFinished
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
    selection: Set<PhotoItem>,
    isSelectionMode: Boolean,
    observeUploadEnqueued: (PhotoItem?) -> Flow<Boolean>,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    healthState: HealthState,
    isNetworkValidated: Boolean,
    onPageChanged: (Int) -> Unit,
    onVisiblePhotoChanged: (Int, PhotoItem?) -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    onSkip: (PhotoItem?) -> Unit,
    onMoveToProcessing: (PhotoItem?) -> Unit,
    onMoveSelection: () -> Unit,
    onEnqueueUpload: (PhotoItem?) -> Unit,
    onUndo: () -> Unit,
    onDelete: (PhotoItem?) -> Unit,
    onDeleteSelection: () -> Unit,
    onDeleteResult: (ViewerViewModel.DeleteResult) -> Unit,
    onWriteRequestResult: (Boolean) -> Unit,
    onJumpToDate: (Instant) -> Unit,
    onScrollToNewest: () -> Unit,
    onPhotoLongPress: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
    onCancelSelection: () -> Unit,
    onSelectFolder: () -> Unit,
    enhancementStrength: Float,
    enhancementInProgress: Boolean,
    enhancementReady: Boolean,
    enhancementResultUri: Uri?,
    isEnhancementResultForCurrentPhoto: Boolean,
    enhancementProgress: Map<Int, Float>,
    onEnhancementStrengthChange: (Float) -> Unit,
    onEnhancementStrengthChangeFinished: () -> Unit,
) {
    BackHandler {
        if (isSelectionMode) {
            onCancelSelection()
        } else {
            onBack()
        }
    }

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
    val isQueuedFlow = remember(currentPhoto?.id, currentPhoto?.uri) {
        observeUploadEnqueued(currentPhoto)
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

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val outcome = when (result.resultCode) {
            Activity.RESULT_OK -> ViewerViewModel.DeleteResult.Success
            Activity.RESULT_CANCELED -> ViewerViewModel.DeleteResult.Cancelled
            else -> ViewerViewModel.DeleteResult.Failed
        }
        onDeleteResult(outcome)
    }
    val writeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onWriteRequestResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(events, context, photos) {
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
                is ViewerViewModel.ViewerEvent.ShowToast -> {
                    Toast.makeText(context, event.messageRes, Toast.LENGTH_SHORT).show()
                }
                is ViewerViewModel.ViewerEvent.RequestDelete -> {
                    val request = IntentSenderRequest.Builder(event.intentSender).build()
                    runCatching { deleteLauncher.launch(request) }
                        .onFailure { onDeleteResult(ViewerViewModel.DeleteResult.Failed) }
                }
                is ViewerViewModel.ViewerEvent.RequestWrite -> {
                    val request = IntentSenderRequest.Builder(event.intentSender).build()
                    runCatching { writeLauncher.launch(request) }
                        .onFailure { onWriteRequestResult(false) }
                }
                ViewerViewModel.ViewerEvent.RefreshPhotos -> {
                    photos.refresh()
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
                onScrollToNewest = onScrollToNewest,
                healthState = healthState,
                isNetworkValidated = isNetworkValidated,
            )
        },
        bottomBar = {
            Column {
                if (!isSelectionMode) {
                    ViewerEnhancementSlider(
                        value = enhancementStrength,
                        inProgress = enhancementInProgress,
                        isReady = enhancementReady,
                        progressByTile = enhancementProgress,
                        onValueChange = onEnhancementStrengthChange,
                        onValueChangeFinished = onEnhancementStrengthChangeFinished
                    )
                }
                val processingBusy = enhancementInProgress ||
                    actionInProgress == ViewerViewModel.ViewerActionInProgress.Processing
                val publishBaseEnabled = !isBusy && currentPhoto != null && !isCurrentQueued && !isSelectionMode
                val publishBlockedByEnhancement = publishBaseEnabled && !enhancementReady
                ViewerActionBar(
                    isSelectionMode = isSelectionMode,
                    selectionCount = selection.size,
                    onCancelSelection = onCancelSelection,
                    onMoveSelection = onMoveSelection,
                    onDeleteSelection = onDeleteSelection,
                    skipEnabled = !isBusy && currentIndex < itemCount - 1 && !isSelectionMode,
                    processingEnabled = !isBusy && currentPhoto != null && !isSelectionMode && !enhancementInProgress,
                    publishEnabled = publishBaseEnabled && enhancementReady,
                    deleteEnabled = !isBusy && currentPhoto != null && !isSelectionMode,
                    processingBusy = processingBusy,
                    publishBusy = actionInProgress == ViewerViewModel.ViewerActionInProgress.Upload,
                    deleteBusy = actionInProgress == ViewerViewModel.ViewerActionInProgress.Delete,
                    canUndo = canUndo && !isBusy && !isSelectionMode,
                    undoCount = undoCount,
                    onSkip = { onSkip(currentPhoto) },
                    onMoveToProcessing = { onMoveToProcessing(currentPhoto) },
                    onEnqueueUpload = { onEnqueueUpload(currentPhoto) },
                    onUndo = onUndo,
                    onDelete = { onDelete(currentPhoto) },
                    publishBlockedByProcessing = publishBlockedByEnhancement
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isSelectionMode) {
                ViewerSelectionGrid(
                    photos = photos,
                    selection = selection,
                    onToggleSelection = onToggleSelection
                )
            } else {
                VerticalPager(
                    state = pagerState,
                    userScrollEnabled = isPagerScrollEnabled && !isBusy,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val item = photos[page]
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("viewer_photo_$page")
                            .let { base ->
                                if (item != null) {
                                    base.pointerInput(item.id) {
                                        detectTapGestures(onLongPress = { onPhotoLongPress(item) })
                                    }
                                } else {
                                    base
                                }
                            }
                    ) {
                        if (item != null) {
                            val displayedUri =
                                if (
                                    page == currentIndex &&
                                    enhancementReady &&
                                    isEnhancementResultForCurrentPhoto &&
                                    enhancementResultUri != null
                                ) {
                                    enhancementResultUri
                                } else {
                                    item.uri
                                }
                            ZoomableImage(
                                uri = displayedUri,
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewerSelectionGrid(
    photos: LazyPagingItems<PhotoItem>,
    selection: Set<PhotoItem>,
    onToggleSelection: (PhotoItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(photos.itemCount) { index ->
            val item = photos[index]
            if (item != null) {
                ViewerSelectionThumbnail(
                    photo = item,
                    selected = selection.contains(item),
                    onToggleSelection = { onToggleSelection(item) },
                    modifier = Modifier.testTag("viewer_selection_$index")
                )
            } else {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewerSelectionThumbnail(
    photo: PhotoItem,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onToggleSelection,
                onLongClick = onToggleSelection
            ),
        shape = MaterialTheme.shapes.small,
        tonalElevation = if (selected) 6.dp else 0.dp,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = photo.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                )
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
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
    onScrollToNewest: () -> Unit,
    onOpenSettings: () -> Unit,
    healthState: HealthState,
    isNetworkValidated: Boolean,
) {
    SmallTopAppBar(
        title = {
            HealthStatusBadge(
                healthState = healthState,
                isNetworkValidated = isNetworkValidated,
            )
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
            IconButton(onClick = onScrollToNewest) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(id = R.string.viewer_open_latest)
                )
            }
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
            for ((labelRes, date) in presets) {
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
    isNetworkValidated: Boolean,
    modifier: Modifier = Modifier,
) {
    val status = if (isNetworkValidated) {
        healthState.status
    } else {
        HealthStatus.OFFLINE
    }
    val (labelRes, color) = when (status) {
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
    isSelectionMode: Boolean,
    selectionCount: Int,
    onCancelSelection: () -> Unit,
    onMoveSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    skipEnabled: Boolean,
    processingEnabled: Boolean,
    publishEnabled: Boolean,
    deleteEnabled: Boolean,
    processingBusy: Boolean,
    publishBusy: Boolean,
    deleteBusy: Boolean,
    canUndo: Boolean,
    undoCount: Int,
    onSkip: () -> Unit,
    onMoveToProcessing: () -> Unit,
    onEnqueueUpload: () -> Unit,
    onUndo: () -> Unit,
    onDelete: () -> Unit,
    publishBlockedByProcessing: Boolean,
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isSelectionMode) {
                SelectionActionContent(
                    selectionCount = selectionCount,
                    processingBusy = processingBusy,
                    deleteBusy = deleteBusy,
                    onCancelSelection = onCancelSelection,
                    onMoveSelection = onMoveSelection,
                    onDeleteSelection = onDeleteSelection
                )
                return@Column
            }
            val buttonHeight = 48.dp
            val primaryModifier = Modifier
                .weight(1f)
                .height(buttonHeight)
            val processingColors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
            val publishColors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            val processingText = if (processingBusy) {
                stringResource(id = R.string.viewer_action_processing_busy)
            } else {
                stringResource(id = R.string.viewer_action_processing)
            }
            val processingStateDescription = if (processingBusy) {
                stringResource(id = R.string.viewer_action_processing_state_busy)
            } else {
                null
            }
            val publishStateDescription = if (publishBlockedByProcessing) {
                stringResource(id = R.string.viewer_action_publish_state_disabled_processing)
            } else {
                null
            }
            val processingSemanticsModifier = processingStateDescription?.let { description ->
                Modifier.semantics { stateDescription = description }
            } ?: Modifier
            val publishSemanticsModifier = publishStateDescription?.let { description ->
                Modifier.semantics { stateDescription = description }
            } ?: Modifier
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onMoveToProcessing,
                    enabled = processingEnabled,
                    modifier = primaryModifier.then(processingSemanticsModifier),
                    colors = processingColors
                ) {
                    ActionButtonContent(
                        text = processingText,
                        busy = processingBusy
                    )
                }
                Button(
                    onClick = onEnqueueUpload,
                    enabled = publishEnabled,
                    modifier = primaryModifier.then(publishSemanticsModifier),
                    colors = publishColors
                ) {
                    ActionButtonContent(
                        text = stringResource(id = R.string.viewer_action_publish),
                        busy = publishBusy
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                UndoActionButton(
                    enabled = canUndo,
                    count = undoCount,
                    onUndo = onUndo
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val skipColors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = onSkip,
                        enabled = skipEnabled,
                        modifier = Modifier.height(buttonHeight),
                        colors = skipColors
                    ) {
                        ActionButtonContent(text = stringResource(id = R.string.viewer_action_skip))
                    }

                    val deleteColors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                    FilledTonalButton(
                        onClick = onDelete,
                        enabled = deleteEnabled,
                        modifier = Modifier.height(buttonHeight),
                        colors = deleteColors
                    ) {
                        ActionButtonContent(
                            text = stringResource(id = R.string.viewer_action_delete),
                            busy = deleteBusy
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionActionContent(
    selectionCount: Int,
    processingBusy: Boolean,
    deleteBusy: Boolean,
    onCancelSelection: () -> Unit,
    onMoveSelection: () -> Unit,
    onDeleteSelection: () -> Unit
) {
    val buttonHeight = 48.dp
    val hasSelection = selectionCount > 0
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.viewer_selection_count, selectionCount),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onCancelSelection) {
                Text(text = stringResource(id = R.string.viewer_selection_cancel))
            }
        }
        val processingColors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        val deleteColors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
        val processingText = if (processingBusy) {
            stringResource(id = R.string.viewer_action_processing_busy)
        } else {
            stringResource(id = R.string.viewer_action_processing)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledTonalButton(
                onClick = onMoveSelection,
                enabled = hasSelection && !processingBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight),
                colors = processingColors
            ) {
                ActionButtonContent(
                    text = processingText,
                    busy = processingBusy
                )
            }
            FilledTonalButton(
                onClick = onDeleteSelection,
                enabled = hasSelection && !deleteBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight),
                colors = deleteColors
            ) {
                ActionButtonContent(
                    text = stringResource(id = R.string.viewer_action_delete),
                    busy = deleteBusy
                )
            }
        }
    }
}

@Composable
private fun UndoActionButton(
    enabled: Boolean,
    count: Int,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val undoTooltipText = stringResource(id = R.string.viewer_action_undo_tooltip)
    val undoContentDescription = stringResource(id = R.string.viewer_action_undo)
    Box(
        modifier = modifier
            .semantics {
                contentDescription = undoContentDescription
                if (!enabled) {
                    stateDescription = undoTooltipText
                }
            }
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            color = if (enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (enabled) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ) {
            IconButton(
                onClick = onUndo,
                enabled = enabled,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Undo,
                    contentDescription = undoContentDescription
                )
            }
        }
        if (count > 0) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp),
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
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
            isSelectionMode = false,
            selectionCount = 0,
            onCancelSelection = {},
            onMoveSelection = {},
            onDeleteSelection = {},
            skipEnabled = true,
            processingEnabled = true,
            publishEnabled = true,
            deleteEnabled = true,
            processingBusy = true,
            publishBusy = false,
            deleteBusy = false,
            canUndo = true,
            undoCount = 3,
            onSkip = {},
            onMoveToProcessing = {},
            onEnqueueUpload = {},
            onUndo = {},
            onDelete = {},
            publishBlockedByProcessing = true
        )
    }
}

@Composable
private fun ViewerEnhancementSlider(
    value: Float,
    inProgress: Boolean,
    isReady: Boolean,
    progressByTile: Map<Int, Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val averageProgress = remember(progressByTile) {
        if (progressByTile.isEmpty()) {
            0f
        } else {
            (progressByTile.values.sum() / progressByTile.size).coerceIn(0f, 1f)
        }
    }
    val percent = (value * 100).roundToInt().coerceIn(0, 100)
    val progressPercent = (averageProgress * 100).roundToInt().coerceIn(0, 100)
    val sliderLabel = stringResource(id = R.string.viewer_improve_label)
    val sliderValueDescription = stringResource(id = R.string.viewer_improve_value, percent)
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sliderLabel,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = sliderValueDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("enhancement_strength_label")
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..1f,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enhancement_slider")
                    .semantics {
                        contentDescription = sliderLabel
                        stateDescription = sliderValueDescription
                    }
            )
            val statusText = when {
                inProgress -> stringResource(id = R.string.viewer_improve_state_running)
                isReady -> stringResource(id = R.string.viewer_improve_state_ready)
                progressByTile.isEmpty() -> stringResource(id = R.string.viewer_improve_state_pending)
                progressPercent >= 100 -> stringResource(id = R.string.viewer_improve_state_ready)
                else -> stringResource(id = R.string.viewer_improve_state_pending)
            }
            if (inProgress || (!isReady && progressByTile.isNotEmpty())) {
                LinearProgressIndicator(
                    progress = averageProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("enhancement_progress")
                )
                Text(
                    text = stringResource(id = R.string.viewer_improve_progress, progressPercent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("enhancement_progress_text")
                )
            }
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("enhancement_loader")
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("enhancement_status_text")
            )
        }
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
