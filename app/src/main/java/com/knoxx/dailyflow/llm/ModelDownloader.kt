package com.knoxx.dailyflow.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Downloads a [ModelSpec] into the app's external files dir — the same place a
 * sideloaded model goes, so both routes converge on one location.
 *
 * Resumable: partial bytes live in a `.part` file next to the target, and a
 * retry sends `Range: bytes=<have>-` rather than starting over. At ~3 GB on
 * venue wifi, restarting from zero on every dropped connection is not a real
 * option.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val BUFFER_BYTES = 1 shl 16 // 64 KB
    private const val MAX_REDIRECTS = 5

    /** Progress is emitted on a timer, not per read. A 64 KB buffer over 3 GB
     *  is ~45,000 reads; emitting each one would swamp the UI with state
     *  updates that recompose faster than anyone can see. */
    private const val PROGRESS_INTERVAL_MS = 400L

    sealed interface Progress {
        data class Running(
            val bytesDownloaded: Long,
            val totalBytes: Long,
            val bytesPerSecond: Long
        ) : Progress {
            val fraction: Float
                get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes) else 0f
        }

        data class Done(val file: File) : Progress
        data class Failed(val reason: String, val resumable: Boolean) : Progress
    }

    fun partFile(context: Context, spec: ModelSpec): File =
        File(ModelLocator.modelDir(context), spec.fileName + ".part")

    fun targetFile(context: Context, spec: ModelSpec): File =
        File(ModelLocator.modelDir(context), spec.fileName)

    /** Bytes already fetched for [spec], for showing "resume from 42%". */
    fun partialBytes(context: Context, spec: ModelSpec): Long =
        partFile(context, spec).let { if (it.exists()) it.length() else 0L }

    fun download(context: Context, spec: ModelSpec): Flow<Progress> = flow {
        val dir = ModelLocator.modelDir(context)
        val part = partFile(context, spec)
        val target = targetFile(context, spec)

        if (target.exists() && target.length() == spec.sizeBytes) {
            emit(Progress.Done(target))
            return@flow
        }

        var already = if (part.exists()) part.length() else 0L

        // A .part larger than the finished file means it's stale or corrupt —
        // a resumed range request from there would produce garbage.
        if (already > spec.sizeBytes) {
            part.delete()
            already = 0L
        }

        val needed = spec.sizeBytes - already
        if (dir.usableSpace < needed + SAFETY_MARGIN_BYTES) {
            emit(
                Progress.Failed(
                    "Not enough free space. Need about ${fmtGb(needed)} GB, " +
                        "${fmtGb(dir.usableSpace)} GB available.",
                    resumable = true
                )
            )
            return@flow
        }

        var connection: HttpURLConnection? = null
        try {
            connection = openFollowingRedirects(spec.url, already)

            val code = connection.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL

            // Asked to resume but got a full body back: the server ignored the
            // Range header, so what follows is byte 0 onward. Appending it to
            // existing bytes would silently corrupt the file.
            if (already > 0 && !resuming) {
                Log.w(TAG, "Server ignored Range (HTTP $code) — restarting download")
                part.delete()
                already = 0L
            }

            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                emit(Progress.Failed(httpErrorMessage(code), resumable = code >= 500))
                return@flow
            }

            var downloaded = already
            var lastEmitAt = System.currentTimeMillis()
            var lastEmitBytes = downloaded
            emit(Progress.Running(downloaded, spec.sizeBytes, 0))

            connection.inputStream.use { input ->
                FileOutputStream(part, /* append = */ already > 0).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()

                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastEmitAt
                        if (elapsed >= PROGRESS_INTERVAL_MS) {
                            val rate = (downloaded - lastEmitBytes) * 1000 / elapsed
                            emit(Progress.Running(downloaded, spec.sizeBytes, rate))
                            lastEmitAt = now
                            lastEmitBytes = downloaded
                        }
                    }
                    output.flush()
                }
            }

            // Validate against the size we actually know, not a guess. A short
            // file here means a truncated transfer, and handing it to the
            // engine produces an opaque native failure instead of a clear one.
            if (part.length() != spec.sizeBytes) {
                emit(
                    Progress.Failed(
                        "Download incomplete — got ${fmtGb(part.length())} GB of " +
                            "${fmtGb(spec.sizeBytes)} GB. Retry to resume.",
                        resumable = true
                    )
                )
                return@flow
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                emit(Progress.Failed("Could not finalize the downloaded file.", resumable = true))
                return@flow
            }

            emit(Progress.Done(target))
        } catch (e: IOException) {
            Log.e(TAG, "Download failed", e)
            // The .part file is deliberately kept — that's what makes retry a
            // resume rather than a restart.
            emit(
                Progress.Failed(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: "Network error. Check your connection and retry.",
                    resumable = true
                )
            )
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Follows redirects by hand rather than via [HttpURLConnection.setInstanceFollowRedirects].
     * Hugging Face redirects `/resolve/main/...` to a CDN host, and the built-in
     * follower drops request headers across hosts — which would silently discard
     * the Range header and turn every resume into a full restart.
     */
    private fun openFollowingRedirects(startUrl: String, rangeFrom: Long): HttpURLConnection {
        var url = startUrl
        repeat(MAX_REDIRECTS) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "DailyFlow/1.0 (Android)")
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }

            return when (conn.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) throw IOException("Redirect without a Location header")
                    url = URL(URL(url), location).toString()
                    return@repeat
                }
                else -> conn
            }
        }
        throw IOException("Too many redirects")
    }

    private fun httpErrorMessage(code: Int): String = when (code) {
        401, 403 -> "Access denied by Hugging Face (HTTP $code). This model should be " +
            "public — check the URL, or sideload the file instead."
        404 -> "Model file not found on the server (HTTP 404)."
        416 -> "Resume failed — the partial file doesn't match the server. Clear it and retry."
        in 500..599 -> "Hugging Face is having trouble (HTTP $code). Retry in a moment."
        else -> "Download failed (HTTP $code)."
    }

    /** Leave headroom so finishing a download doesn't fill the device. */
    private const val SAFETY_MARGIN_BYTES = 300L * 1024 * 1024

    private fun fmtGb(bytes: Long): String = "%.1f".format(bytes / 1_000_000_000.0)
}
