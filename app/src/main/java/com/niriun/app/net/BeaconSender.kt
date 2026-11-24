package com.niriun.app.net

import com.niriun.app.protocol.Wire
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Announces the device with the 280-byte hello over UDP multicast, on every multicast-capable interface,
 * every [Wire.BEACON_INTERVAL_MS]. The interface list is re-read each round so Wi-Fi/hotspot/USB tethering
 * changes are picked up.
 */
class BeaconSender(private val intervalMs: Long = Wire.BEACON_INTERVAL_MS, private val hello: () -> ByteArray) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::loop, "niriun-beacon").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(2_000)
        thread = null
    }

    private fun loop() {
        val groupV4 = InetAddress.getByName(Wire.BEACON_GROUP_V4)
        val groupV6 = InetAddress.getByName(Wire.BEACON_GROUP_V6)
        while (running.get()) {
            val payload = hello()
            for (ni in multicastInterfaces()) {
                val hasV4 = ni.interfaceAddresses.any { it.address is Inet4Address }
                val hasV6 = ni.interfaceAddresses.any { it.address is Inet6Address }
                if (hasV4) sendOn(ni, groupV4, payload)
                if (hasV6) sendOn(ni, groupV6, payload)
            }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun multicastInterfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
    } catch (_: IOException) {
        emptyList()
    }

    private fun sendOn(ni: NetworkInterface, group: InetAddress, payload: ByteArray) {
        try {
            MulticastSocket().use { s ->
                s.networkInterface = ni
                s.timeToLive = 1
                s.send(DatagramPacket(payload, payload.size, group, Wire.BEACON_PORT))
            }
        } catch (_: IOException) {
            // Interfaces without a usable route (e.g. cellular) simply fail; ignore.
        }
    }
}
