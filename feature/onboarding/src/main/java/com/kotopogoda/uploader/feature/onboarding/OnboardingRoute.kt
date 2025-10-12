package com.kotopogoda.uploader.feature.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import com.kotopogoda.uploader.feature.onboarding.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingRoute(
    onOpenViewer: (Int) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val currentFolderUri = (uiState as? OnboardingUiState.FolderSelected)?.treeUri

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
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
                viewModel.onFolderSelected(uri.toString())
            } catch (_: SecurityException) {
                // ignore and leave the state unchanged
            }
        }
    }

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is OnboardingEvent.OpenViewer -> onOpenViewer(event.startIndex)
            }
        }
    }

    OnboardingScreen(
        uiState = uiState,
        onSelectFolder = {
            folderPickerLauncher.launch(currentFolderUri?.let(Uri::parse))
        },
        onStartReview = viewModel::onStartReview,
        onResetProgress = viewModel::onResetProgress,
        onResetAnchor = viewModel::onResetAnchor
    )
}

@Composable
private fun OnboardingScreen(
    uiState: OnboardingUiState,
    onSelectFolder: () -> Unit,
    onStartReview: (ReviewStartOption, Instant?) -> Unit,
    onResetProgress: () -> Unit,
    onResetAnchor: () -> Unit
) {
    when (uiState) {
        OnboardingUiState.Loading -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        }

        OnboardingUiState.FolderNotSelected -> {
            FolderSelectionPrompt(
                title = stringResource(id = R.string.onboarding_choose_folder_title),
                body = stringResource(id = R.string.onboarding_choose_folder_body),
                primaryButtonLabel = stringResource(id = R.string.onboarding_choose_folder_action),
                onPrimaryButtonClick = onSelectFolder
            )
        }

        is OnboardingUiState.FolderSelected -> {
            FolderSelectedContent(
                folderUri = uiState.treeUri,
                progress = uiState.progress,
                photoCount = uiState.photoCount,
                onChangeFolder = onSelectFolder,
                onStartReview = onStartReview,
                onResetProgress = onResetProgress,
                onResetAnchor = onResetAnchor
            )
        }
    }
}

@Composable
private fun FolderSelectionPrompt(
    title: String,
    body: String,
    primaryButtonLabel: String,
    onPrimaryButtonClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onPrimaryButtonClick) {
            Text(text = primaryButtonLabel)
        }
    }
}

@Composable
private fun FolderSelectedContent(
    folderUri: String,
    progress: ReviewPosition?,
    photoCount: Int,
    onChangeFolder: () -> Unit,
    onStartReview: (ReviewStartOption, Instant?) -> Unit,
    onResetProgress: () -> Unit,
    onResetAnchor: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = stringResource(id = R.string.onboarding_selected_folder_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(id = R.string.onboarding_selected_folder_body, folderUri),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        OutlinedButton(onClick = onChangeFolder) {
            Text(text = stringResource(id = R.string.onboarding_change_folder))
        }
        Spacer(modifier = Modifier.weight(1f))
        ReviewStartSection(
            progress = progress,
            photoCount = photoCount,
            onStartReview = onStartReview,
            onResetProgress = onResetProgress,
            onResetAnchor = onResetAnchor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStartSection(
    progress: ReviewPosition?,
    photoCount: Int,
    onStartReview: (ReviewStartOption, Instant?) -> Unit,
    onResetProgress: () -> Unit,
    onResetAnchor: () -> Unit
) {
    val formatter = rememberDateFormatter()
    val anchorText = progress?.anchorDate?.let { formatInstant(it, formatter) }
    val continueHint = when {
        photoCount == 0 -> stringResource(id = R.string.onboarding_start_option_continue_no_photos)
        progress != null -> stringResource(
            id = R.string.onboarding_start_option_continue_hint,
            progress.index + 1
        )
        else -> stringResource(id = R.string.onboarding_start_option_continue_hint_first)
    }
    val newHint = anchorText?.let {
        stringResource(id = R.string.onboarding_start_option_new_with_anchor, it)
    } ?: stringResource(id = R.string.onboarding_start_option_new_without_anchor)

    var selectedOption by rememberSaveable { mutableStateOf(ReviewStartOption.CONTINUE) }
    var selectedDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    val selectedDateText = selectedDateMillis?.let { selectedMillis ->
        formatInstant(Instant.ofEpochMilli(selectedMillis), formatter)
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedDateMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                    }
                ) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.onboarding_start_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(id = R.string.onboarding_start_photos_count, photoCount),
            style = MaterialTheme.typography.bodyMedium
        )
        StartOptionRow(
            option = ReviewStartOption.CONTINUE,
            selectedOption = selectedOption,
            onSelect = { selectedOption = it },
            title = stringResource(id = R.string.onboarding_start_option_continue),
            subtitle = continueHint
        )
        StartOptionRow(
            option = ReviewStartOption.NEW,
            selectedOption = selectedOption,
            onSelect = { selectedOption = it },
            title = stringResource(id = R.string.onboarding_start_option_new),
            subtitle = newHint
        )
        StartOptionRow(
            option = ReviewStartOption.DATE,
            selectedOption = selectedOption,
            onSelect = { selectedOption = it },
            title = stringResource(id = R.string.onboarding_start_option_date),
            subtitle = selectedDateText
                ?: stringResource(id = R.string.onboarding_start_option_date_hint)
        )
        if (selectedOption == ReviewStartOption.DATE) {
            TextButton(onClick = { showDatePicker = true }) {
                Text(text = stringResource(id = R.string.onboarding_start_pick_date))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onResetProgress,
                enabled = progress != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(id = R.string.onboarding_reset_progress))
            }
            OutlinedButton(
                onClick = onResetAnchor,
                enabled = progress?.anchorDate != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(id = R.string.onboarding_reset_anchor))
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val date = if (selectedOption == ReviewStartOption.DATE) {
                    selectedDateMillis?.let(Instant::ofEpochMilli)
                } else {
                    null
                }
                onStartReview(selectedOption, date)
            },
            enabled = selectedOption != ReviewStartOption.DATE || selectedDateMillis != null
        ) {
            Text(text = stringResource(id = R.string.onboarding_start_button))
        }
    }
}

@Composable
private fun StartOptionRow(
    option: ReviewStartOption,
    selectedOption: ReviewStartOption,
    onSelect: (ReviewStartOption) -> Unit,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = option == selectedOption,
                onClick = { onSelect(option) }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = option == selectedOption,
            onClick = null
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun rememberDateFormatter(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = remember(context) {
        val locales = context.resources.configuration.locales
        if (!locales.isEmpty) locales[0] else Locale.getDefault()
    }
    return remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
}

private fun formatInstant(instant: Instant, formatter: DateTimeFormatter): String =
    formatter.format(instant)
