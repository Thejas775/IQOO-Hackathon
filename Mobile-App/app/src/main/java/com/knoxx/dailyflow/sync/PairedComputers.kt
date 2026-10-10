package com.knoxx.dailyflow.sync

import android.content.Context
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A PC this phone has paired with, and where each side's sync stands. */
data class PairedComputer(
    val id: String,
    val name: String,
    val key: ByteArray,
    val host: String? = null,
    val port: Int = SyncProtocol.SYNC_PORT,
    /** Highest PC change number applied here — sent so the PC sends only newer ones. */
    val pcCursor: Long = 0,
    /** Highest local change number the PC has accepted. */
    val pushedSeq: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String? = null
)

/**
 * This phone's identity and its paired computers, in SharedPreferences.
 *
 * Kept out of Room on purpose: "Erase everything" clears the memory table, and
 * erasing memories must not also silently unpair the PC. Never backed up —
 * the manifest sets allowBackup=false — because the keys here can read the
 * owner's memories off the PC.
 */
object PairedComputers {

    private const val PREFS = "dailyflow_pc_sync"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_COMPUTERS = "computers"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    /** What the PC shows under "Phones" and on synced cards. */
    fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(40)
    }

    @Synchronized
    fun all(context: Context): List<PairedComputer> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COMPUTERS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PairedComputer(
                id = o.getString("id"),
                name = o.getString("name"),
                key = Base64.decode(o.getString("key"), Base64.NO_WRAP),
                host = if (o.isNull("host")) null else o.optString("host"),
                port = o.optInt("port", SyncProtocol.SYNC_PORT),
                pcCursor = o.optLong("pcCursor"),
                pushedSeq = o.optLong("pushedSeq"),
                lastSyncAt = o.optLong("lastSyncAt"),
                lastError = if (o.isNull("lastError")) null else o.optString("lastError")
            )
        }
    }

    @Synchronized
    fun save(context: Context, computer: PairedComputer) {
        write(context, all(context).filter { it.id != computer.id } + computer)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, all(context).filter { it.id != id })
    }

    private fun write(context: Context, computers: List<PairedComputer>) {
        val array = JSONArray()
        computers.forEach { c ->
            array.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("key", Base64.encodeToString(c.key, Base64.NO_WRAP))
                    .put("host", c.host ?: JSONObject.NULL)
                    .put("port", c.port)
                    .put("pcCursor", c.pcCursor)
                    .put("pushedSeq", c.pushedSeq)
                    .put("lastSyncAt", c.lastSyncAt)
                    .put("lastError", c.lastError ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMPUTERS, array.toString()).apply()
    }
}
