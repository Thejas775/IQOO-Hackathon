package com.knoxx.dailyflow.vision

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Destination URIs for `ACTION_IMAGE_CAPTURE`.
 *
 * Uses the system camera app through an intent rather than embedding CameraX.
 * For "take a picture of this" that's the whole feature in a few lines, it
 * gives the user their familiar camera UI with all its focus and HDR handling,
 * and — because the app never declares `CAMERA` — it needs no runtime
 * permission at all. Declaring that permission would actually make things
 * worse: `ACTION_IMAGE_CAPTURE` starts *requiring* a grant once the manifest
 * asks for it.
 */
object CameraCapture {

    private const val CAPTURE_DIR = "camera"

    /** A fresh FileProvider URI the camera app can write into. */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /** Drops previous captures — the prepared JPEG is what actually gets used,
     *  so the original full-resolution shot is dead weight once converted. */
    fun clearCaptures(context: Context) {
        File(context.cacheDir, CAPTURE_DIR).listFiles()?.forEach { it.delete() }
    }
}
