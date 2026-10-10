package com.knoxx.dailyflow.sync

import android.util.Base64
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.SyncTombstone
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phone <-> PC sync protocol, v1. The PC side is
 * Desktop-App/dailyflow/sync_protocol.py, which documents every byte; the two
 * must change together.
 *
 * Raw sockets with length-prefixed JSON rather than HTTP: Android blocks
 * cleartext HTTP by default, and a network-security exception cannot be
 * scoped to "any address on my Wi-Fi". The payload is encrypted end to end
 * here anyway, so HTTP would add nothing but that obstacle.
 */
object SyncProtocol {

    const val DISCOVERY_PORT = 47812
    const val SYNC_PORT = 47811
    const val DISCOVER_MAGIC = "DAILYFLOW_DISCOVER_1"
    private const val MAX_FRAME = 16 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 30_000

    private val random = SecureRandom()

    /** One request frame, one reply frame. Blocking — call on Dispatchers.IO. */
    fun request(host: String, port: Int, message: JSONObject): JSONObject {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = DataOutputStream(socket.getOutputStream())
            val bytes = message.toString().toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)          // 4-byte big-endian, as Python's ">I"
            out.write(bytes)
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readInt()
            require(length in 0..MAX_FRAME) { "reply too large ($length bytes)" }
            val reply = ByteArray(length)
            input.readFully(reply)
            return JSONObject(String(reply, Charsets.UTF_8))
        }
    }

    /** AES-256-GCM, 12-byte nonce, AAD = this phone's id; ciphertext||tag,
     *  which is the layout Python's AESGCM produces and expects. */
    fun seal(key: ByteArray, deviceId: String, body: JSONObject): JSONObject {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(body.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
    }

    fun open(key: ByteArray, deviceId: String, message: JSONObject): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, Base64.decode(message.getString("nonce"), Base64.DEFAULT))
        )
        cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
        val pt = cipher.doFinal(Base64.decode(message.getString("ct"), Base64.DEFAULT))
        return JSONObject(String(pt, Charsets.UTF_8))
    }

    /** A memory on the wire. Raw screen text is never included — the owner
     *  chose (2026-10-10) that only what DailyFlow understood travels. */
    fun rowToJson(m: MemoryEntity, phoneId: String, phoneName: String): JSONObject =
        JSONObject()
            .put("syncId", m.syncId)
            .put(
                "origin",
                JSONObject()
                    .put("id", m.originDevice ?: phoneId)
                    .put("name", m.originName ?: phoneName)
                    .put("kind", m.originKind ?: "phone")
            )
            .put("timestamp", m.timestamp)
            .put("source", m.packageName)
            .put("eventType", m.eventType ?: JSONObject.NULL)
            .put("summary", m.summary ?: JSONObject.NULL)
            .put("sourceQuote", m.sourceQuote ?: JSONObject.NULL)
            .put("direction", m.direction ?: JSONObject.NULL)
            .put("dueAt", m.dueAt ?: JSONObject.NULL)
            .put("dueAtExplicit", m.dueAtExplicit)
            .put("personName", m.personName ?: JSONObject.NULL)
            .put("category", m.category ?: JSONObject.NULL)
            .put("projectName", m.projectName ?: JSONObject.NULL)
            .put("commitmentDone", m.commitmentDone)
            .put("updatedAt", m.updatedAt)

    fun tombstoneToJson(t: SyncTombstone): JSONObject =
        JSONObject().put("syncId", t.syncId).put("deletedAt", t.deletedAt)
}

/** org.json's optString turns JSON null into "" and optLong turns it into 0;
 *  both would store a wrong value rather than no value. */
internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

internal fun JSONObject.longOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key)
