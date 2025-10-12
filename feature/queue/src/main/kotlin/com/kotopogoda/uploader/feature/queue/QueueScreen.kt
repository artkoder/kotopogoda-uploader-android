package com.kotopogoda.uploader.feature.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus

@Composable
fun QueueRoute(
    modifier: Modifier = Modifier,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val healthState by viewModel.healthState.collectAsState()
    QueueScreen(healthState = healthState, modifier = modifier)
}

@Composable
fun QueueScreen(
    healthState: HealthState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        Text(text = "Очередь загрузок", style = MaterialTheme.typography.headlineSmall)
        HealthBadge(healthState = healthState)
        Text(text = "Здесь будет список задач загрузки")
    }
}

@Composable
fun HealthBadge(healthState: HealthState, modifier: Modifier = Modifier) {
    val (label, color) = when (healthState.status) {
        HealthStatus.ONLINE -> "Online" to MaterialTheme.colorScheme.tertiary
        HealthStatus.DEGRADED -> "Degraded" to MaterialTheme.colorScheme.secondary
        HealthStatus.OFFLINE -> "Offline" to MaterialTheme.colorScheme.error
        HealthStatus.UNKNOWN -> "Unknown" to MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
