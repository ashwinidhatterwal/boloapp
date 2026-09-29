package com.offlinenarrator.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.activity.compose.setContent
import com.offlinenarrator.benchmark.benchmark.BenchmarkViewModel
import com.offlinenarrator.benchmark.ui.BenchmarkScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BenchmarkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                BenchmarkScreen(viewModel)
            }
        }
    }
}
