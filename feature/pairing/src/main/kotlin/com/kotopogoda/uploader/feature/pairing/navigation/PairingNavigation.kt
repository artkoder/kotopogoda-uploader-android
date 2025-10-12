package com.kotopogoda.uploader.feature.pairing.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.kotopogoda.uploader.feature.pairing.ui.PairingRoute

const val PairingRoute = "pairing"

fun NavGraphBuilder.pairingScreen(onPaired: () -> Unit) {
    composable(PairingRoute) {
        PairingRoute(onPaired = onPaired)
    }
}
