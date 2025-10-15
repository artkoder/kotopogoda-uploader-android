package com.kotopogoda.uploader.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.feature.onboarding.OnboardingRoute
import com.kotopogoda.uploader.feature.viewer.ViewerRoute
import com.kotopogoda.uploader.feature.viewer.VIEWER_ROUTE_PATTERN
import com.kotopogoda.uploader.feature.viewer.VIEWER_START_INDEX_ARG
import com.kotopogoda.uploader.feature.viewer.viewerRoute
import androidx.navigation.navArgument
import com.kotopogoda.uploader.feature.queue.QUEUE_ROUTE
import com.kotopogoda.uploader.feature.queue.QueueRoute
import com.kotopogoda.uploader.feature.pairing.navigation.PairingRoute
import com.kotopogoda.uploader.feature.pairing.navigation.pairingScreen
import com.kotopogoda.uploader.feature.status.StatusRoute
import com.kotopogoda.uploader.feature.status.navigation.STATUS_ROUTE
import com.kotopogoda.uploader.permissions.MediaPermissionGate
import com.kotopogoda.uploader.ui.SettingsRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

private const val SETTINGS_ROUTE = "settings"

enum class AppDestination(val route: String, val startRoute: String) {
    Onboarding("onboarding", "onboarding"),
    Viewer(VIEWER_ROUTE_PATTERN, viewerRoute())
}

@Composable
fun KotopogodaNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    deviceCreds: DeviceCreds?,
    healthState: HealthState,
    onResetPairing: () -> Unit,
    navigationEvents: Flow<AppNavigationEvent>? = null,
) {
    val viewModel: AppStartDestinationViewModel = hiltViewModel()
    val startDestination by viewModel.startDestination.collectAsState()

    val resolvedStartDestination = startDestination
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val navigateBack = remember(navController, activity) {
        {
            val navigated = navController.navigateUp()
            if (!navigated) {
                activity?.finish()
            }
        }
    }

    LaunchedEffect(navigationEvents) {
        navigationEvents?.let { events ->
            events.collectLatest { event ->
                when (event) {
                    AppNavigationEvent.OpenQueue -> navController.navigate(QUEUE_ROUTE) {
                        launchSingleTop = true
                    }
                    AppNavigationEvent.OpenStatus -> navController.navigate(STATUS_ROUTE) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    LaunchedEffect(deviceCreds, resolvedStartDestination) {
        val startDestination = resolvedStartDestination ?: return@LaunchedEffect
        val targetRoute = if (deviceCreds == null) {
            PairingRoute
        } else {
            startDestination.startRoute
        }
        navController.navigate(targetRoute) {
            popUpTo(0)
            launchSingleTop = true
        }
    }

    if (resolvedStartDestination == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        NavHost(
            navController = navController,
            startDestination = resolvedStartDestination.startRoute,
            modifier = modifier
        ) {
            pairingScreen(onPaired = {
                val target = resolvedStartDestination.startRoute
                navController.navigate(target) {
                    popUpTo(0)
                    launchSingleTop = true
                }
            })
            composable(AppDestination.Onboarding.route) {
                MediaPermissionGate {
                    OnboardingRoute(
                        onOpenViewer = { startIndex ->
                            navController.navigate(viewerRoute(startIndex)) {
                                popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                            }
                        }
                    )
                }
            }
            composable(
                route = AppDestination.Viewer.route,
                arguments = listOf(
                    navArgument(VIEWER_START_INDEX_ARG) {
                        type = NavType.IntType
                        defaultValue = 0
                    }
                )
            ) {
                MediaPermissionGate {
                    ViewerRoute(
                        onBack = navigateBack,
                        onOpenQueue = { navController.navigate(QUEUE_ROUTE) },
                        onOpenStatus = { navController.navigate(STATUS_ROUTE) },
                        onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                        healthState = healthState,
                    )
                }
            }
            composable(QUEUE_ROUTE) {
                QueueRoute(
                    onBack = navigateBack,
                    healthState = healthState,
                )
            }
            composable(SETTINGS_ROUTE) {
                SettingsRoute(
                    onBack = navigateBack,
                    onResetPairing = {
                        onResetPairing()
                        navController.navigate(PairingRoute) {
                            popUpTo(0)
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(STATUS_ROUTE) {
                StatusRoute(
                    onBack = navigateBack,
                    onOpenQueue = { navController.navigate(QUEUE_ROUTE) },
                    onOpenPairingSettings = { navController.navigate(SETTINGS_ROUTE) },
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
