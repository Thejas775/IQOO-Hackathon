package com.knoxx.dailyflow.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns whatever the camera or picker hands back into a modest JPEG the vision
 * encoder can take.
 *
 * Phone cameras produce 12–50 MP images. Feeding one straight in wastes a large
 * amount of prefill time re-encoding detail the ViT immediately throws away —
 * it works at a fixed, much smaller input resolution — and risks an OOM while
 * decoding the full bitmap. Downscaling first is both faster and safer, and
 * costs nothing in answer quality at normal photo subjects.
 */
object ImagePrep {

    private const val TAG = "ImagePrep"

    /** Longest edge after downscaling. Comfortably above the encoder's own
     *  input size, so text in a photographed document stays legible for OCR
     *  while the bitmap stays small. */
    private const val MAX_EDGE_PX = 1024

    private const val JPEG_QUALITY = 90

    /**
     * Decodes [source], corrects orientation, downscales, and writes a JPEG
     * into cacheDir. Returns the file, or null if the image couldn't be read.
     *
     * The result is a real file rather than a byte array because the engine is
     * handed `Content.ImageFile` — that avoids holding a second full copy of
     * the image in memory just to base64 it.
     */
    suspend fun prepare(context: Context, source: Uri): File? = withContext(Dispatchers.IO) {
        try {
            // The elvis must guard the *stream*, not the decode result. With
            // inJustDecodeBounds = true, decodeStream deliberately returns null
            // and reports through Options instead — so folding both into one
            // `?.use { ... } ?: return` expression made this fail every single
            // time, for every image, before it read a byte.
            val boundsStream = context.contentResolver.openInputStream(source)
            if (boundsStream == null) {
                Log.e(TAG, "openInputStream returned null for $source")
                return@withContext null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e(TAG, "Could not read image bounds for $source (${bounds.outWidth}x${bounds.outHeight})")
                return@withContext null
            }

            // Cheap power-of-two downsample during decode, so the full-size
            // bitmap never has to exist in memory at all.
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val pixelStream = context.contentResolver.openInputStream(source)
            if (pixelStream == null) {
                Log.e(TAG, "openInputStream returned null on second open for $source")
                return@withContext null
            }
            var bitmap: Bitmap = pixelStream.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: run {
                    Log.e(TAG, "decodeStream returned null for $source")
                    return@withContext null
                }

            bitmap = applyExifRotation(context, source, bitmap)
            bitmap = scaleToMaxEdge(bitmap)

            val out = File(context.cacheDir, "vision_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            bitmap.recycle()
            out
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to prepare image", t)
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE_PX) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE_PX) return bitmap
        val ratio = MAX_EDGE_PX.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Phone cameras usually store the sensor image unrotated and record the
     * orientation in EXIF instead. Without this, a photo taken in portrait
     * arrives sideways — the model then describes a rotated scene, and OCR on
     * a sideways document returns nothing useful.
     */
    private fun applyExifRotation(context: Context, source: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(source)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read EXIF orientation", t)
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }

        return try {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to rotate; using image as-is", e)
            bitmap
        }
    }

    /** Removes prepared images from earlier turns so cacheDir doesn't grow
     *  without bound across a long session. */
    fun clearCache(context: Context, except: File? = null) {
        context.cacheDir
            .listFiles { f -> f.name.startsWith("vision_") && f.name.endsWith(".jpg") }
            ?.forEach { if (it != except) it.delete() }
    }
}
