package com.kotopogoda.uploader.feature.viewer

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val ViewerRoute = "viewer"

fun NavGraphBuilder.viewerScreen(
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable(ViewerRoute) {
        ViewerScreen(onOpenQueue = onOpenQueue, onOpenSettings = onOpenSettings)
    }
}
