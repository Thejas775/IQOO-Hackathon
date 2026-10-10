package com.knoxx.dailyflow.sync

import android.content.Context
import android.net.ConnectivityManager
import com.knoxx.dailyflow.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/** A PC running DailyFlow that answered on this Wi-Fi. */
data class FoundComputer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val pairing: Boolean
)

/**
 * Finds DailyFlow PCs on the current network by UDP broadcast.
 *
 * Sent to both the subnet's own broadcast address and 255.255.255.255: some
 * routers drop the limited broadcast, others drop the directed one. Networks
 * that isolate clients from each other (many guest and office Wi-Fis) drop
 * both, which is why the Add computer screen also takes an address by hand.
 */
object LanDiscovery {

    suspend fun find(context: Context, waitMs: Long = 1_500): List<FoundComputer> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, FoundComputer>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val payload = SyncProtocol.DISCOVER_MAGIC.toByteArray(Charsets.US_ASCII)
                    broadcastAddresses(context).forEach { address ->
                        runCatching {
                            socket.send(DatagramPacket(payload, payload.size, address, SyncProtocol.DISCOVERY_PORT))
                        }
                    }
                    val deadline = System.currentTimeMillis() + waitMs
                    val buffer = ByteArray(2048)
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val reply = runCatching {
                            JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        }.getOrNull() ?: continue
                        if (reply.optString("app") != "dailyflow") continue
                        val id = reply.optString("pcId")
                        if (id.isEmpty()) continue
                        found[id] = FoundComputer(
                            id = id,
                            name = reply.optString("name", "Computer"),
                            host = packet.address.hostAddress ?: continue,
                            port = reply.optInt("port", SyncProtocol.SYNC_PORT),
                            pairing = reply.optBoolean("pairing")
                        )
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("pcsync", "discovery failed: ${e.message}")
            }
            found.values.toList()
        }

    private fun broadcastAddresses(context: Context): List<InetAddress> {
        val result = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return result
        val link = cm.getLinkProperties(cm.activeNetwork) ?: return result
        link.linkAddresses.forEach { la ->
            val address = la.address as? Inet4Address ?: return@forEach
            val prefix = la.prefixLength
            if (prefix !in 1..30) return@forEach
            val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            val mask = -1 shl (32 - prefix)
            val bcast = ip or mask.inv()
            val bytes = ByteArray(4) { i -> (bcast shr (24 - 8 * i) and 0xff).toByte() }
            result.add(InetAddress.getByAddress(bytes))
        }
        return result
    }
}
