package com.kotopogoda.uploader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus
import com.kotopogoda.uploader.feature.onboarding.onboardingScreen
import com.kotopogoda.uploader.feature.pairing.navigation.PairingRoute
import com.kotopogoda.uploader.feature.pairing.navigation.pairingScreen
import com.kotopogoda.uploader.feature.queue.QueueRoute
import com.kotopogoda.uploader.feature.queue.queueScreen
import com.kotopogoda.uploader.feature.queue.HealthBadge
import com.kotopogoda.uploader.feature.viewer.ViewerRoute
import com.kotopogoda.uploader.feature.viewer.viewerScreen
import dagger.hilt.android.AndroidEntryPoint

private const val SettingsRoute = "settings"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            KotopogodaUploaderRoot()
        }
    }
}

@Composable
fun KotopogodaUploaderRoot(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()

    LaunchedEffect(uiState.deviceCreds) {
        if (uiState.deviceCreds == null) {
            navController.navigate(PairingRoute) {
                popUpTo(0)
            }
        } else {
            navController.navigate(ViewerRoute) {
                popUpTo(0)
            }
        }
    }

    KotopogodaScaffold(
        navController = navController,
        snackbarHostState = snackbarHostState,
        healthState = uiState.healthState,
        onResetPairing = viewModel::clearPairing,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KotopogodaScaffold(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    healthState: HealthState,
    onResetPairing: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Kotopogoda") },
                actions = {
                    HealthStatusIndicator(healthState = healthState)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = PairingRoute,
            modifier = Modifier.padding(padding),
        ) {
            pairingScreen(onPaired = {
                navController.navigate(ViewerRoute) {
                    popUpTo(0)
                }
            })
            viewerScreen(
                onOpenQueue = { navController.navigate(QueueRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
            queueScreen()
            onboardingScreen(onFinished = { navController.navigate(ViewerRoute) })
            androidx.navigation.compose.composable(SettingsRoute) {
                SettingsScreen(onResetPairing = {
                    onResetPairing()
                    navController.navigate(PairingRoute) {
                        popUpTo(0)
                    }
                })
            }
        }
    }
}

@Composable
private fun HealthStatusIndicator(healthState: HealthState) {
    when (healthState.status) {
        HealthStatus.UNKNOWN -> Text(text = "—", style = MaterialTheme.typography.labelLarge)
        else -> HealthBadge(healthState = healthState)
    }
}
