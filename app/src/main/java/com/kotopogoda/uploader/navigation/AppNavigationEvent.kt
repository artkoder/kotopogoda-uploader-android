package com.kotopogoda.uploader.navigation

sealed interface AppNavigationEvent {
    data object OpenQueue : AppNavigationEvent
    data object OpenStatus : AppNavigationEvent
}
