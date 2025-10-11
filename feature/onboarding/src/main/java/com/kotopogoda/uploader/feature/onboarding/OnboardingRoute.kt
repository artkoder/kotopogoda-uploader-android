package com.kotopogoda.uploader.feature.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.feature.onboarding.R

@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
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
                            runCatching {
                                contentResolver.releasePersistableUriPermission(
                                    persistedPermission.uri,
                                    persistedPermission.flags
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

    OnboardingScreen(
        uiState = uiState,
        onSelectFolder = {
            folderPickerLauncher.launch(currentFolderUri?.let(Uri::parse))
        },
        onContinue = onFinished
    )
}

@Composable
private fun OnboardingScreen(
    uiState: OnboardingUiState,
    onSelectFolder: () -> Unit,
    onContinue: () -> Unit
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
                onChangeFolder = onSelectFolder,
                onContinue = onContinue
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
    onChangeFolder: () -> Unit,
    onContinue: () -> Unit
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
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onContinue
        ) {
            Text(text = stringResource(id = R.string.onboarding_continue))
        }
    }
}
