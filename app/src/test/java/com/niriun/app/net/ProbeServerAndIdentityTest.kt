package com.niriun.app.net

import com.niriun.app.protocol.PacketBuilder
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeServerTest {
    @Test
    fun answersWithTheHelloAndLetsThePeerClose() {
        val id = ByteArray(16) { (0xF0 + it).toByte() }
        val server = ProbeServer(0) { PacketBuilder.hello(id, "Probe phone") }
        server.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                s.soTimeout = 5_000
                val hello = ByteArray(280).also { DataInputStream(s.getInputStream()).readFully(it) }
                assertArrayEquals(id, hello.copyOfRange(4, 20))
                assertEquals("Probe phone", String(hello, 24, 11))
            }
        } finally {
            server.stop()
        }
    }
}

class DeviceNameTest {
    @Test
    fun brandIsPrependedUnlessTheModelAlreadyContainsIt() {
        assertEquals("Google Pixel 9", DeviceIdentity.displayName(brand = "google", model = "Pixel 9"))
        assertEquals("Samsung SM-S918B", DeviceIdentity.displayName(brand = "samsung", model = "Samsung SM-S918B"))
    }
}
