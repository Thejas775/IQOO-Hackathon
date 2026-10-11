package com.knoxx.dailyflow.llm

import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Minimal 16 kHz mono WAV handling for the audio benchmark.
 *
 * The benchmark needs clips of an exact duration, repeatedly, so it builds them rather
 * than asking anyone to speak for three minutes on cue. One short source clip is looped
 * or truncated to whatever length is being measured.
 *
 * That is sound for timing: the audio encoder runs at a fixed frame rate, so its cost is
 * a function of duration, not of what is being said. It is *not* sound for judging
 * transcript quality — for that, record a real clip at least as long as the run.
 *
 * Format matches [com.knoxx.dailyflow.voice.VoiceRecorder] exactly: 16 kHz, mono,
 * 16-bit PCM, 44-byte RIFF header. Anything else gets a decode error from the encoder
 * rather than a transcript.
 */
object WavTools {

    /** The encoder's native rate, as in VoiceRecorder. */
    const val SAMPLE_RATE = 16_000

    /** Size of the RIFF header VoiceRecorder writes. */
    const val HEADER_BYTES = 44

    /** Bytes of PCM per second: mono, two bytes per sample. */
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    /** Strips the header and returns raw PCM, or an empty array if the file is too short. */
    fun pcmFrom(file: File): ByteArray {
        if (!file.isFile || file.length() <= HEADER_BYTES) return ByteArray(0)
        val all = file.readBytes()
        return all.copyOfRange(HEADER_BYTES, all.size)
    }

    /** Seconds of audio in a PCM buffer. */
    fun seconds(pcm: ByteArray): Double = pcm.size.toDouble() / BYTES_PER_SECOND

    /**
     * Writes [pcm] to [file] as a complete WAV.
     *
     * The header is written up front here, unlike VoiceRecorder which has to patch it
     * afterwards: the length is already known, because the buffer is already complete.
     */
    fun writeWav(file: File, pcm: ByteArray): File {
        val byteRate = SAMPLE_RATE * 2
        file.outputStream().use { out ->
            out.write("RIFF".toByteArray())
            out.write(intLe(36 + pcm.size))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intLe(16))          // PCM header size
            out.write(shortLe(1))         // PCM, uncompressed
            out.write(shortLe(1))         // mono
            out.write(intLe(SAMPLE_RATE))
            out.write(intLe(byteRate))
            out.write(shortLe(2))         // block align
            out.write(shortLe(16))        // bits per sample
            out.write("data".toByteArray())
            out.write(intLe(pcm.size))
            out.write(pcm)
        }
        return file
    }

    /**
     * Builds a clip of exactly [targetSeconds] by looping or truncating [source].
     *
     * Looping repeats audible content rather than padding with silence, so the encoder
     * sees a realistic signal for the whole duration instead of a short clip followed by
     * nothing.
     */
    fun makeClip(source: ByteArray, targetSeconds: Int, into: File): File {
        require(source.isNotEmpty()) { "No source audio" }
        val target = targetSeconds * BYTES_PER_SECOND
        val out = ByteArray(target)
        var written = 0
        while (written < target) {
            val take = minOf(source.size, target - written)
            System.arraycopy(source, 0, out, written, take)
            written += take
        }
        return writeWav(into, out)
    }

    /**
     * A synthetic source so the benchmark can run with no microphone and no recording.
     *
     * Two tones near the low formants of speech, amplitude-modulated at roughly a
     * syllable rate. It is not speech and will not transcribe to anything sensible —
     * it exists so encode *timing* can be measured before anyone has recorded a word.
     */
    fun tone(durationSeconds: Int): ByteArray {
        val samples = durationSeconds * SAMPLE_RATE
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = 0.5 + 0.5 * sin(2 * PI * 4.0 * t)       // ~4 syllables/sec
            val wave = sin(2 * PI * 220.0 * t) * 0.6 + sin(2 * PI * 740.0 * t) * 0.4
            val v = (wave * envelope * 9000).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (v and 0xff).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return pcm
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
}
