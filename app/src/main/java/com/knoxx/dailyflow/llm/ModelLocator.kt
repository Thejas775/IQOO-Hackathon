package com.knoxx.dailyflow.llm

import android.content.Context
import java.io.File

/**
 * Finds a usable .litertlm model in the app's external files dir.
 *
 * Two routes land in the same place, on purpose:
 *  - in-app download (see [ModelDownloader]) — the normal path
 *  - `adb push` — the fallback when the network is unusable
 *
 * Scans for *any* .litertlm rather than a fixed filename, so a manually pushed
 * build works without matching whatever the catalog happens to name. Partial
 * downloads are `.part` files and are skipped by the extension filter.
 */
object ModelLocator {

    /** Below this, treat the file as an interrupted transfer rather than a
     *  model — handing a truncated file to the engine yields an opaque native
     *  error instead of a clear "not downloaded yet". */
    private const val MIN_PLAUSIBLE_BYTES = 500L * 1024 * 1024

    fun modelDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** Where to `adb push` a model, shown in the UI as the offline fallback. */
    fun pushPath(context: Context): String =
        "/sdcard/Android/data/${context.packageName}/files/"

    /** Largest valid model present, or null. Largest wins so that a full E4B
     *  build is preferred over a smaller one left behind from earlier testing. */
    fun find(context: Context): File? =
        modelDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }
            ?.filter { it.length() >= MIN_PLAUSIBLE_BYTES }
            ?.maxByOrNull { it.length() }

    /** Every usable model on disk, for a "switch model" affordance later. */
    fun findAll(context: Context): List<File> =
        modelDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }
            ?.filter { it.length() >= MIN_PLAUSIBLE_BYTES }
            ?.sortedByDescending { it.length() }
            .orEmpty()
}
