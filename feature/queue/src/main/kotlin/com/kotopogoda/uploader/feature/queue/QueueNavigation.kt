package com.kotopogoda.uploader.feature.queue

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val QueueRoute = "queue"

fun NavGraphBuilder.queueScreen() {
    composable(QueueRoute) {
        QueueRoute()
    }
}
