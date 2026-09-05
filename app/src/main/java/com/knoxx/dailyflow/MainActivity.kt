package com.knoxx.dailyflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knoxx.dailyflow.ui.BenchScreen
import com.knoxx.dailyflow.ui.theme.DailyFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DailyFlowTheme {
                val vm: BenchViewModel = viewModel()
                val state by vm.state.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BenchScreen(
                        state = state,
                        onSend = vm::send,
                        onStop = vm::stop,
                        onSetBackend = vm::setBackend,
                        onRetryModel = vm::checkModel,
                        onClearRuns = vm::clearRuns,
                        onSetMaxNumTokens = vm::setMaxNumTokens,
                        onSelectSpec = vm::selectSpec,
                        onStartDownload = vm::startDownload,
                        onCancelDownload = vm::cancelDownload,
                        onDiscardPartial = vm::discardPartial,
                        onAttachImage = vm::attachImage,
                        onClearImage = vm::clearAttachedImage,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
