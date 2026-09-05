package com.knoxx.dailyflow.data

/** One app's capture volume, for the "Manage app access" screen. */
data class PackageCount(
    val packageName: String,
    val captureCount: Int
)
