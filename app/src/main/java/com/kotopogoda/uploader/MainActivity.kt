package com.kotopogoda.uploader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
                    KotopogodaNavHost()
                }
            }
        }
    }
}
