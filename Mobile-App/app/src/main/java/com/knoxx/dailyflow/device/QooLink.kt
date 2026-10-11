package com.knoxx.dailyflow.device

import android.util.Log
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The link to the QOO watch. See Hardware-Code/Qoo_Display/main/net.h for the other end.
 *
 * Protocol, deliberately line-based so it can be driven by hand with nc while debugging:
 *
 *   discovery   device --UDP broadcast--> :5151   "QOO <code> <ip>", once a second
 *   pairing     phone  --TCP :5152-->  "PAIR <code>\n"  -->  "OK\n" or "NO\n"
 *   audio       device --> "AUDIO <bytes>\n" then exactly that many bytes of raw
 *               16 kHz mono 16-bit PCM
 *
 * The code is what picks one device out of however many are on the network, which is the
 * real reason it exists. The device broadcasts rather than the phone scanning a subnet:
 * a broadcast is one packet and works without knowing anything about the network.
 *
 * Raw PCM crosses the wire, not WAV — the header needs a length only known once the
 * recording stops, and [com.knoxx.dailyflow.llm.WavTools] already writes one.
 */
class QooLink {

    private var socket: Socket? = null
    private var input: DataInputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Listens for the device's broadcast and returns the IP of the one announcing [code].
     *
     * Returns null on timeout. The commonest causes, in order: the device has not joined
     * the network yet, the phone and the device are on different networks, or the network
     * has client isolation enabled — a hotspot never does, which is why one is the safer
     * choice.
     */
    suspend fun discover(code: String, timeoutMs: Int = 12_000): String? = withContext(Dispatchers.IO) {
        var udp: DatagramSocket? = null
        try {
            udp = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(ANNOUNCE_PORT))
                soTimeout = 1_000
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(128)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (t: SocketTimeoutException) {
                    continue                     // keep waiting until the overall deadline
                }
                val parts = String(packet.data, 0, packet.length).trim().split(" ")
                if (parts.size == 3 && parts[0] == "QOO" && parts[1] == code) {
                    Log.i(TAG, "found device ${parts[2]} for code $code")
                    return@withContext parts[2]
                }
            }
            null
        } catch (t: Throwable) {
            Log.e(TAG, "discovery failed", t)
            null
        } finally {
            udp?.close()
        }
    }

    /** Opens the control socket and presents the code. Returns true if the device accepts. */
    suspend fun connect(ip: String, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            close()
            val s = Socket()
            s.connect(InetSocketAddress(ip, PAIR_PORT), 5_000)
            // No read timeout: after pairing this socket sits idle for as long as it
            // takes someone to press the device, and a timeout would read that patience
            // as a dropped link.
            s.soTimeout = 0
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val stream = DataInputStream(s.getInputStream())
            out.write("PAIR $code\n".toByteArray())
            out.flush()
            val reply = readLine(stream)
            if (reply != "OK") {
                Log.w(TAG, "device refused the code: $reply")
                s.close()
                return@withContext false
            }
            socket = s
            input = stream
            true
        } catch (t: Throwable) {
            Log.e(TAG, "connect failed", t)
            false
        }
    }

    /** What the watch sent, and why. The bytes are identical; the intent is not. */
    enum class Kind {
        /** Something to remember. Goes through extraction and is saved. */
        MEMORY,

        /** A question. Expects an answer spoken back. */
        QUESTION
    }

    /** One message from the watch. */
    data class Message(val kind: Kind, val pcm: ByteArray)

    /**
     * Blocks until the watch sends a recording, then returns it with its intent.
     *
     * Returns null when the link drops, which is the receive loop's exit condition.
     */
    suspend fun awaitMessage(): Message? = withContext(Dispatchers.IO) {
        val stream = input ?: return@withContext null
        try {
            while (true) {
                val line = readLine(stream) ?: return@withContext null
                val kind = when {
                    line.startsWith("AUDIO ") -> Kind.MEMORY
                    line.startsWith("ASK ") -> Kind.QUESTION
                    else -> continue                            // ignore anything unrecognised
                }
                val expected = line.substringAfter(' ').trim().toIntOrNull() ?: continue
                if (expected <= 0 || expected > MAX_AUDIO_BYTES) {
                    Log.w(TAG, "implausible audio length $expected")
                    return@withContext null
                }
                // readFully, not read: a multi-megabyte payload arrives in many TCP
                // segments, and a single read returns whatever happens to have landed.
                val pcm = ByteArray(expected)
                stream.readFully(pcm)
                Log.i(TAG, "received $expected bytes as $kind")
                return@withContext Message(kind, pcm)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "link closed while waiting for audio", t)
            null
        }
    }

    /**
     * Sends the answer in words, for the watch to put on its screen.
     *
     * Sent before the audio, and not treated as optional: a speaker can be unplugged,
     * broken or drowned out, and an answer nobody can read or hear is no answer at all.
     * Trimmed to what the watch will hold, on a word boundary rather than mid-word.
     */
    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        try {
            // ASCII only: the device font covers printable ASCII, and a smart quote or
            // an em dash from the model would otherwise arrive as bytes it cannot draw.
            val clean = text.replace('’', '\'').replace('‘', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('—', '-').replace('–', '-')
                .filter { it.code in 32..126 || it == '\n' }
            val capped = if (clean.length <= MAX_TEXT_CHARS) clean else {
                val cut = clean.take(MAX_TEXT_CHARS)
                cut.substringBeforeLast(' ', cut) + "..."
            }
            val bytes = capped.toByteArray(Charsets.US_ASCII)
            val out = s.getOutputStream()
            out.write("TEXT ${bytes.size}\n".toByteArray())
            out.write(bytes)
            out.flush()
            Log.i(TAG, "sent ${bytes.size} bytes of answer text")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not send the answer text", t)
            false
        }
    }

    /**
     * Sends a spoken answer back for the watch to play. 16 kHz mono 16-bit PCM.
     *
     * Chunked rather than one write: a multi-hundred-kilobyte answer will not leave in
     * a single call, and the watch reads exactly the length the header promised, so a
     * short write would desynchronise the stream rather than merely truncating audio.
     */
    suspend fun sendReply(pcm: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        try {
            val out = s.getOutputStream()
            out.write("REPLY ${pcm.size}\n".toByteArray())
            var sent = 0
            while (sent < pcm.size) {
                val chunk = minOf(4096, pcm.size - sent)
                out.write(pcm, sent, chunk)
                sent += chunk
            }
            out.flush()
            Log.i(TAG, "sent ${pcm.size} bytes of answer")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not send the answer", t)
            false
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        input = null
    }

    /** Reads bytes up to a newline. Returns null at end of stream. */
    private fun readLine(stream: DataInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) builder.append(b.toChar())
        }
        return builder.toString()
    }

    companion object {
        private const val TAG = "QooLink"
        const val ANNOUNCE_PORT = 5151
        const val PAIR_PORT = 5152

        /** Sanity bound: the device caps a take at 180 s, which is 5.76 MB of PCM.
         *  Anything larger means the stream has desynchronised, and allocating it
         *  would turn a protocol bug into an OutOfMemoryError. */
        const val MAX_AUDIO_BYTES = 8 * 1024 * 1024

        /** Matches the watch's text buffer, less room for the terminator. */
        const val MAX_TEXT_CHARS = 480
    }
}
