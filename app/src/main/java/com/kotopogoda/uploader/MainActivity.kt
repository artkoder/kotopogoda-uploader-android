package com.kotopogoda.uploader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.kotopogoda.uploader.navigation.KotopogodaNavHost
import com.kotopogoda.uploader.ui.theme.KotopogodaUploaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KotopogodaUploaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KotopogodaApp()
                }
            }
        }
    }
}

@Composable
private fun KotopogodaApp(
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    KotopogodaNavHost(
        navController = navController,
        deviceCreds = uiState.deviceCreds,
        healthState = uiState.healthState,
        onResetPairing = viewModel::clearPairing,
    )
}
