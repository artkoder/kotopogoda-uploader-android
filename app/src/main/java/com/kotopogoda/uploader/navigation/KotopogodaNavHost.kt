package com.kotopogoda.uploader.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    NavHost(
        navController = navController,
        startDestination = AppDestination.Onboarding.route,
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
