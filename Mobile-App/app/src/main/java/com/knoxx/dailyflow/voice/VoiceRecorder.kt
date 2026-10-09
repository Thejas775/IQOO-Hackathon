package com.knoxx.dailyflow.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Records a note as 16 kHz mono WAV, which is what the model's audio encoder
 * expects.
 *
 * AudioRecord rather than MediaRecorder, which cannot produce WAV — it emits
 * AAC in an MP4 container, and handing that to the encoder gets a decode error
 * rather than a transcript. Raw PCM plus a hand-written 44-byte header is the
 * shortest path to a file Gemma will actually read.
 */
class VoiceRecorder(private val context: Context) {

    /** The encoder's native rate. Recording higher and downsampling would be
     *  wasted work; recording lower loses consonants, which is where a name or
     *  a date is usually lost. */
    private val sampleRate = 16_000

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isRecording: Boolean = false
        private set

    fun outputFile(): File = File(context.cacheDir, "voice-note.wav")

    /**
     * Starts recording. The caller must already hold RECORD_AUDIO — this
     * cannot request it, and AudioRecord fails silently without it rather than
     * throwing, which is why the caller checks first.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord reports no usable buffer size")
            return false
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not open the microphone", t)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.e(TAG, "Microphone opened but did not initialise")
            return false
        }

        val file = outputFile()
        record = recorder
        isRecording = true
        recorder.startRecording()

        job = scope.launch {
            file.outputStream().use { out ->
                // Placeholder header, rewritten on stop once the length of the
                // audio is known. WAV puts two sizes in the header, and
                // neither can be filled in before the recording ends.
                out.write(ByteArray(HEADER_BYTES))
                val buffer = ByteArray(minBuffer)
                while (isRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) out.write(buffer, 0, read)
                }
            }
            writeHeader(file)
        }
        return true
    }

    /** Stops and returns the finished file, or null if nothing was captured. */
    suspend fun stop(): File? {
        if (!isRecording) return null
        isRecording = false

        record?.runCatching {
            stop()
            release()
        }
        record = null
        job?.join()
        job = null

        val file = outputFile()
        return if (file.length() > HEADER_BYTES) file else null
    }

    fun cancel() {
        isRecording = false
        record?.runCatching { stop(); release() }
        record = null
        job?.cancel()
        job = null
    }

    /**
     * Fills in the RIFF header now that the data length is known.
     *
     * Written by hand because the platform has no WAV writer. The two sizes
     * are the only fields that cannot be known up front: the total file size
     * minus 8, and the length of the data chunk.
     */
    private fun writeHeader(file: File) {
        val dataLength = (file.length() - HEADER_BYTES).toInt()
        if (dataLength <= 0) return
        val byteRate = sampleRate * 2 // mono, 16-bit

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.write(intLe(36 + dataLength))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(intLe(16))            // PCM header size
            raf.write(shortLe(1))           // PCM, uncompressed
            raf.write(shortLe(1))           // mono
            raf.write(intLe(sampleRate))
            raf.write(intLe(byteRate))
            raf.write(shortLe(2))           // block align
            raf.write(shortLe(16))          // bits per sample
            raf.write("data".toByteArray())
            raf.write(intLe(dataLength))
        }
    }

    private fun intLe(value: Int) = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun shortLe(value: Int) = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte()
    )

    private companion object {
        const val TAG = "VoiceRecorder"
        const val HEADER_BYTES = 44
    }
}
