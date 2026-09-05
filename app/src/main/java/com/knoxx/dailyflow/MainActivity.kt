package com.knoxx.dailyflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.llm.GemmaEngine
import com.knoxx.dailyflow.ui.DailyFlowApp
import com.knoxx.dailyflow.ui.theme.DailyFlowTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        DebugLog.log("perm", "POST_NOTIFICATIONS granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge: the design runs content under the status bar and lets
        // the nav bar float over the list, so the app draws the full window
        // and each screen applies its own insets.
        enableEdgeToEdge()
        askForNotificationsIfNeeded()
        setContent {
            DailyFlowTheme {
                DailyFlowApp()
            }
        }
    }

    /**
     * Asks for POST_NOTIFICATIONS, which on API 33+ must be granted at runtime
     * even though it is declared in the manifest.
     *
     * This was declared and never requested, and the cost was not only that
     * reminders stayed silent. WorkManager cannot promote a worker to the
     * foreground without being able to post its notification, so
     * ExtractionWorker's setForeground call is refused and it returns
     * Result.retry() — meaning the scheduled pass never actually ran, with no
     * error anywhere obvious. Measured on the demo device: granted=false.
     *
     * Asked on launch rather than behind a rationale screen because both
     * things it gates — a due reminder, and reading the day at all — are the
     * app's entire function rather than an optional extra.
     */
    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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
