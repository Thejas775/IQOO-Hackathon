package com.knoxx.dailyflow

import android.app.Application
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.CaptureStats
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ml.EmbeddingEngine
import com.knoxx.dailyflow.work.ExtractionNotification
import com.knoxx.dailyflow.work.ExtractionScheduler

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
        ExtractionScheduler.schedulePeriodic(this)
    }
}
