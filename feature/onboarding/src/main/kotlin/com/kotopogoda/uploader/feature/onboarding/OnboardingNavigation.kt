package com.kotopogoda.uploader.feature.onboarding

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val OnboardingRoute = "onboarding"

fun NavGraphBuilder.onboardingScreen(onFinished: () -> Unit) {
    composable(OnboardingRoute) {
        OnboardingScreen(onGetStarted = onFinished)
    }
}
