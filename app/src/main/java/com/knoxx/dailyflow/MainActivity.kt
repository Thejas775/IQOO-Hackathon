package com.knoxx.dailyflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.knoxx.dailyflow.ui.DailyFlowApp
import com.knoxx.dailyflow.ui.theme.DailyFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge: the design runs content under the status bar and lets
        // the nav bar float over the list, so the app draws the full window
        // and each screen applies its own insets.
        enableEdgeToEdge()
        setContent {
            DailyFlowTheme {
                DailyFlowApp()
            }
        }
    }
}
