package com.lidarbotsystem.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lidarbotsystem.app.ui.LidarScreen
import com.lidarbotsystem.app.ui.theme.LidarBotSystemTheme
import com.lidarbotsystem.app.viewmodel.LidarViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: LidarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LidarBotSystemTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LidarScreen(viewModel = viewModel)
                }
            }
        }
    }
}
