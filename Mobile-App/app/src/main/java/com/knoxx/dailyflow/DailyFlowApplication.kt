package com.knoxx.dailyflow

import android.app.Application
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.CaptureStats
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.ml.EmbeddingEngine
import com.knoxx.dailyflow.sync.PairedComputers
import com.knoxx.dailyflow.sync.PcSyncScheduler
import com.knoxx.dailyflow.ui.theme.ThemePreference
import com.knoxx.dailyflow.work.ExtractionNotification
import com.knoxx.dailyflow.work.ExtractionScheduler
import com.knoxx.dailyflow.work.ReminderScheduler

/**
 * Initialises shared state as early as possible.
 *
 * This matters because the accessibility service and the activity are separate
 * entry points into the same process, and either can be first: the service
 * starts at boot if capture was left on, long before anyone opens the app.
 *
 * Nothing here loads Gemma. The model is only ever loaded inside the promoted
 * foreground pass (see [ExtractionScheduler]) or when the user asks something
 * in Ask — loading it on app start would cost ten seconds and several
 * gigabytes before anyone had asked for anything.
 */
class DailyFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything can draw: the palette has to be in place for the
        // first frame or a cold start flashes the other identity.
        ThemePreference.load(this)

        DebugLog.init(this)
        MemoryRepository.init(this)
        CapturePreferences.init(this)
        CaptureStats.init(this)

        // Cheap and lazy: the embedder is a 26 MB model bundled in the APK and
        // only loads on first use, so wiring it here costs nothing until
        // something actually asks for a vector.
        MemoryRepository.setEmbedder(EmbeddingEngine.modelId(this)) { text ->
            EmbeddingEngine.embed(this, text)
        }

        // Every 15 minutes, promoted to a foreground service: load, drain the
        // queue, unload. Steady-state memory between passes is zero, which is
        // what makes running a multi-gigabyte model on a schedule affordable.
        ExtractionNotification.ensureChannel(this)
        ReminderScheduler.ensureChannel(this)
        ExtractionScheduler.schedulePeriodic(this)

        // PC sync. Only does anything once a computer is paired (You → Add
        // computer): every 15 minutes on Wi-Fi, a few seconds after any
        // change here, and once now so opening the app brings in what the
        // PC learned meanwhile.
        MemoryRepository.setOnLocalChange { PcSyncScheduler.requestSoon(this) }
        if (PairedComputers.all(this).isNotEmpty()) {
            PcSyncScheduler.schedulePeriodic(this)
            PcSyncScheduler.requestSoon(this, delaySeconds = 2)
        }
    }
}
