package com.kotopogoda.uploader.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.feature.onboarding.OnboardingRoute
import com.kotopogoda.uploader.feature.viewer.ViewerRoute
import com.kotopogoda.uploader.feature.viewer.VIEWER_ROUTE_PATTERN
import com.kotopogoda.uploader.feature.viewer.VIEWER_START_INDEX_ARG
import com.kotopogoda.uploader.feature.viewer.viewerRoute
import androidx.navigation.navArgument
import com.kotopogoda.uploader.feature.queue.QUEUE_ROUTE
import com.kotopogoda.uploader.feature.queue.QueueRoute

enum class AppDestination(val route: String, val startRoute: String) {
    Onboarding("onboarding", "onboarding"),
    Viewer(VIEWER_ROUTE_PATTERN, viewerRoute())
}

@Composable
fun KotopogodaNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val viewModel: AppStartDestinationViewModel = hiltViewModel()
    val startDestination by viewModel.startDestination.collectAsState()

    val resolvedStartDestination = startDestination

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
            composable(AppDestination.Onboarding.route) {
                OnboardingRoute(
                    onFinished = {
                        navController.navigate(viewerRoute()) {
                            popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                        }
                    }
                )
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
                ViewerRoute(
                    onBack = { navController.popBackStack() },
                    onOpenQueue = { navController.navigate(QUEUE_ROUTE) }
                )
            }
            composable(QUEUE_ROUTE) {
                QueueRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}
