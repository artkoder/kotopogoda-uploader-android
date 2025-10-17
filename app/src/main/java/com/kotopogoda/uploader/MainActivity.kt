package com.kotopogoda.uploader

import android.content.Context
import android.content.Intent
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
import com.kotopogoda.uploader.navigation.AppNavigationEvent
import com.kotopogoda.uploader.navigation.KotopogodaNavHost
import com.kotopogoda.uploader.ui.theme.KotopogodaUploaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val navigationEvents = MutableSharedFlow<AppNavigationEvent>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            KotopogodaUploaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KotopogodaApp(navigationEvents = navigationEvents)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_DESTINATION)) {
            DEST_QUEUE -> navigationEvents.tryEmit(AppNavigationEvent.OpenQueue)
            DEST_STATUS -> navigationEvents.tryEmit(AppNavigationEvent.OpenStatus)
        }
    }

    companion object {
        private const val EXTRA_DESTINATION = "com.kotopogoda.uploader.EXTRA_DESTINATION"
        private const val DEST_QUEUE = "queue"
        private const val DEST_STATUS = "status"

        fun createOpenQueueIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, DEST_QUEUE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun createOpenStatusIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DESTINATION, DEST_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Composable
private fun KotopogodaApp(
    viewModel: MainViewModel = hiltViewModel(),
    navigationEvents: Flow<AppNavigationEvent>? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()

    KotopogodaNavHost(
        navController = navController,
        deviceCreds = uiState.deviceCreds,
        healthState = uiState.healthState,
        isNetworkValidated = uiState.isNetworkValidated,
        onResetPairing = viewModel::clearPairing,
        navigationEvents = navigationEvents,
    )
}
