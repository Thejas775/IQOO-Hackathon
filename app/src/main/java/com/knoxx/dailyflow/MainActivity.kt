package com.knoxx.dailyflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.knoxx.dailyflow.llm.GemmaEngine
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

    /**
     * Releases the model when the app leaves the screen.
     *
     * A loaded Gemma is several gigabytes of GPU and mapped memory. Held while
     * backgrounded, the process becomes the most attractive thing on the
     * device to kill. Letting it go costs a reload the next time something
     * needs it, which is a much better trade.
     *
     * This only affects a model loaded *by the UI* — for Ask, say. The
     * scheduled extraction pass runs as a promoted foreground service and
     * manages its own load and unload, so it is unaffected by the activity
     * going away, which is the whole point of promoting it.
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing || !isChangingConfigurations) {
            GemmaEngine.requestUnload()
        }
    }
}
