package com.kotopogoda.uploader.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.kotopogoda.uploader.R

@Composable
fun MediaPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val permissions = remember { mediaPermissionsFor(Build.VERSION.SDK_INT) }
    var hasPermission by remember { mutableStateOf(arePermissionsGranted(context, permissions)) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        hasPermission = arePermissionsGranted(context, permissions)
        if (!hasPermission && permissions.any { grantedMap[it] == false }) {
            permissionRequested = true
        }
    }

    LaunchedEffect(permissions) {
        hasPermission = arePermissionsGranted(context, permissions)
    }

    if (hasPermission) {
        content()
    } else {
        val settingsIntent = remember { buildSettingsIntent(context) }
        MediaPermissionPrompt(
            modifier = modifier,
            onGrant = {
                permissionRequested = true
                launcher.launch(permissions.toTypedArray())
            },
            onOpenSettings = {
                context.startActivity(settingsIntent)
            },
            showSettings = permissionRequested
        )
    }
}

@Composable
private fun MediaPermissionPrompt(
    modifier: Modifier = Modifier,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    showSettings: Boolean
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.media_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(id = R.string.media_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Button(onClick = onGrant) {
            Text(text = stringResource(id = R.string.media_permission_grant))
        }
        if (showSettings) {
            TextButton(onClick = onOpenSettings) {
                Text(text = stringResource(id = R.string.media_permission_settings))
            }
        }
    }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun arePermissionsGranted(context: Context, permissions: List<String>): Boolean {
    return permissions.all { permission -> isPermissionGranted(context, permission) }
}

private fun buildSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@VisibleForTesting
internal fun mediaPermissionsFor(apiLevel: Int): List<String> {
    val readPermission = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    return buildList {
        add(readPermission)
        if (apiLevel >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
}
