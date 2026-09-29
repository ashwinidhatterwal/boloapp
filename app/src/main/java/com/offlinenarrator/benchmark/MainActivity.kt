package com.offlinenarrator.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.offlinenarrator.benchmark.app.BoloViewModel
import com.offlinenarrator.benchmark.ui.BoloScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BoloViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                BoloScreen(viewModel)
            }
        }
    }
}
