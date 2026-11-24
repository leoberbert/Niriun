package com.niriun.app.net

import com.niriun.app.protocol.MessageType
import com.niriun.app.protocol.Packet
import com.niriun.app.protocol.PacketBuilder
import com.niriun.app.protocol.VideoMode
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlServerTest {
    private val events = LinkedBlockingQueue<String>()
    private val packets = CopyOnWriteArrayList<Packet>()
    private val disconnected = CountDownLatch(1)
    private var server: ControlServer? = null
    private var client: Socket? = null

    private val id = ByteArray(16) { it.toByte() }

    private fun startServer(idleTimeoutMs: Long = 10_000): ControlServer {
        lateinit var s: ControlServer
        s = ControlServer(
            port = 0,
            listener = object : ControlServer.Listener {
                override fun onConnected(peer: String) {
                    events.add("connected")
                    // Same announcement order as the app: hello, sizes, cameras.
                    s.send(PacketBuilder.hello(id, "Test phone"))
                    s.send(PacketBuilder.sizes(listOf(VideoMode(640, 480, 30)), microphone = false))
                    s.send(PacketBuilder.cameras(listOf("Back camera"), 0))
                }

                override fun onPacket(packet: Packet) {
                    packets.add(packet)
                    events.add("packet:${packet.type}")
                }

                override fun onDisconnected() {
                    events.add("disconnected")
                    disconnected.countDown()
                }
            },
            idleTimeoutMs = idleTimeoutMs,
        )
        s.start()
        server = s
        return s
    }

    private fun connect(s: ControlServer): Socket =
        Socket(InetAddress.getLoopbackAddress(), s.localPort).also {
            it.soTimeout = 5_000
            client = it
        }

    private fun expect(event: String) {
        val got = events.poll(5, TimeUnit.SECONDS)
        assertEquals(event, got)
    }

    @After
    fun tearDown() {
        client?.close()
        server?.stop()
    }

    @Test
    fun announcesHelloSizesAndCamerasOnConnect() {
        val s = startServer()
        val input = DataInputStream(connect(s).getInputStream())
        expect("connected")

        val hello = ByteArray(280).also { input.readFully(it) }
        assertEquals(0x01000118, ByteBuffer.wrap(hello).order(ByteOrder.LITTLE_ENDIAN).getInt(0))
        assertEquals("Test phone", String(hello, 24, 10))

        val sizes = ByteArray(12 + 12).also { input.readFully(it) }
        assertEquals(MessageType.SIZES, (sizes[3].toInt() and 0xFF))
        val cameras = ByteArray(12 + 256).also { input.readFully(it) }
        assertEquals(MessageType.CAMERAS, (cameras[3].toInt() and 0xFF))
    }

    @Test
    fun deliversPacketsFromThePeerEvenWhenTheyArriveInPieces() {
        val s = startServer()
        val c = connect(s)
        expect("connected")
        val start = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(PacketBuilder.header(MessageType.START, 24)).putInt(1).putInt(1).putInt(1280).putInt(720).putFloat(30f).array()
        c.tcpNoDelay = true
        c.getOutputStream().apply {
            write(start, 0, 10)
            flush()
            Thread.sleep(150)
            write(start, 10, 14)
            flush()
        }
        expect("packet:${MessageType.START}")
        assertEquals(24, packets.single().length)
    }

    @Test
    fun streamsLargeVideoPacketsToTheClient() {
        val s = startServer()
        val c = connect(s)
        expect("connected")
        val input = DataInputStream(c.getInputStream())
        input.readFully(ByteArray(280 + 24 + 268))

        val payload = ByteArray(600_000) { (it % 251).toByte() }
        val dst = ByteArray(8 + payload.size)
        val total = PacketBuilder.putVideo(dst, 0.5f, ByteBuffer.wrap(payload), payload.size)
        // 600 KB exceeds the socket buffers, so the write only completes while the client drains it.
        val sent = AtomicBoolean(false)
        val writer = Thread { sent.set(s.send(dst, total)) }.also { it.start() }

        val received = ByteArray(total).also { input.readFully(it) }
        assertEquals(payload.last(), received.last())
        assertEquals(MessageType.VIDEO, received[3].toInt() and 0xFF)
        writer.join(5_000)
        assertTrue(sent.get())
    }

    @Test
    fun dropsTheConnectionOnAnInvalidFrame() {
        val s = startServer()
        val c = connect(s)
        expect("connected")
        c.getOutputStream().write(byteArrayOf(1, 0, 0, 9)) // length 1 < header size
        c.getOutputStream().flush()
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        assertFalse(s.isConnected)
    }

    @Test
    fun dropsTheConnectionAfterTheIdleTimeout() {
        val s = startServer(idleTimeoutMs = 400)
        connect(s)
        expect("connected")
        assertTrue("idle peer must be dropped", disconnected.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun acceptsANewClientAfterTheFirstLeaves() {
        val s = startServer()
        connect(s).close()
        expect("connected")
        expect("disconnected")
        val second = connect(s)
        expect("connected")
        assertEquals(0x18, second.getInputStream().read()) // low byte of the 0x01000118 hello header
    }

    @Test
    fun sendWithoutAClientReturnsFalse() {
        val s = startServer()
        assertFalse(s.send(byteArrayOf(1, 2, 3)))
    }
}
