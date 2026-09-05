package com.knoxx.dailyflow

import android.app.Application
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.CaptureStats
import com.knoxx.dailyflow.data.MemoryRepository

/**
 * Initialises shared state as early as possible.
 *
 * This matters because the accessibility service and the activity are separate
 * entry points into the same process, and either can be first: the service
 * starts at boot if capture was left on, long before anyone opens the app.
 * Initialising here rather than in the activity means a capture arriving
 * before the UI has ever been shown still finds a working repository.
 */
class DailyFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MemoryRepository.init(this)
        CapturePreferences.init(this)
        CaptureStats.init(this)
    }
}
