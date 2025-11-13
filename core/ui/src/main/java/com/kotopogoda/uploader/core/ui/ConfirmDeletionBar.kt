package com.kotopogoda.uploader.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kotopogoda.uploader.core.ui.R

@Composable
fun ConfirmDeletionBar(
    pendingCount: Int,
    inProgress: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(id = R.string.confirm_deletion_button, pendingCount)
    val enabled = pendingCount > 0 && !inProgress
    FilledTonalButton(
        onClick = onConfirm,
        enabled = enabled,
        modifier = modifier.heightIn(min = 40.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(16.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfirmDeletionBarPreview() {
    MaterialTheme {
        ConfirmDeletionBar(
            pendingCount = 3,
            inProgress = false,
            onConfirm = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfirmDeletionBarLoadingPreview() {
    MaterialTheme {
        ConfirmDeletionBar(
            pendingCount = 5,
            inProgress = true,
            onConfirm = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
