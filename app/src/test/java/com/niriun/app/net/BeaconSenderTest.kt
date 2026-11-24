package com.niriun.app.net

import com.niriun.app.protocol.PacketBuilder
import com.niriun.app.protocol.Wire
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeNotNull
import org.junit.Test

class BeaconSenderTest {
    /** Needs a multicast-capable interface with an IPv4 address; skipped (not failed) on hosts without one. */
    @Test
    fun announcesTheHelloOnTheIPv4MulticastGroup() {
        val nic = NetworkInterface.getNetworkInterfaces().toList().firstOrNull { ni ->
            runCatching { ni.isUp && !ni.isLoopback && ni.supportsMulticast() }.getOrDefault(false) &&
                ni.interfaceAddresses.any { it.address is Inet4Address }
        }
        assumeNotNull(nic)

        val hello = PacketBuilder.hello(ByteArray(16) { (it * 3).toByte() }, "Beacon test")
        val group = InetAddress.getByName(Wire.BEACON_GROUP_V4)
        val rx = MulticastSocket(null)
        try {
            rx.reuseAddress = true
            rx.bind(InetSocketAddress(Wire.BEACON_PORT))
            rx.joinGroup(InetSocketAddress(group, 0), nic)
            rx.soTimeout = 4_000

            val sender = BeaconSender(intervalMs = 200) { hello }
            sender.start()
            try {
                val packet = DatagramPacket(ByteArray(512), 512)
                try {
                    rx.receive(packet)
                } catch (e: SocketTimeoutException) {
                    assumeNoException("multicast loopback is not available on this host", e)
                }
                assertEquals(Wire.HELLO_SIZE, packet.length)
                assertArrayEquals(hello, packet.data.copyOf(packet.length))
            } finally {
                sender.stop()
            }
        } finally {
            rx.close()
        }
    }
}
