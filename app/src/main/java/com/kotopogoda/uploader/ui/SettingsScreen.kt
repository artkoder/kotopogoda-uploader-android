package com.kotopogoda.uploader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kotopogoda.uploader.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onResetPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showDialog = remember { mutableStateOf(false) }

    if (showDialog.value) {
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text(text = stringResource(id = R.string.settings_reset_title)) },
            text = { Text(text = stringResource(id = R.string.settings_reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog.value = false
                        onResetPairing()
                    }
                ) {
                    Text(text = stringResource(id = R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog.value = false }) {
                    Text(text = stringResource(id = R.string.settings_reset_cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            SmallTopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(id = R.string.settings_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Top)
        ) {
            Text(
                text = stringResource(id = R.string.settings_pairing_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { showDialog.value = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(id = R.string.settings_reset_button))
            }
        }
    }
}
