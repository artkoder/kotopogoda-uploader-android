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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.kotopogoda.uploader.feature.onboarding.OnboardingRoute
import com.kotopogoda.uploader.feature.viewer.ViewerRoute

enum class AppDestination(val route: String) {
    Onboarding("onboarding"),
    Viewer("viewer")
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
            startDestination = resolvedStartDestination.route,
            modifier = modifier
        ) {
            composable(AppDestination.Onboarding.route) {
                OnboardingRoute(
                    onFinished = {
                        navController.navigate(AppDestination.Viewer.route) {
                            popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(AppDestination.Viewer.route) {
                ViewerRoute()
            }
        }
    }
}
