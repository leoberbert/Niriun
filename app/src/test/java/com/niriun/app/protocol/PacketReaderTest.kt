package com.niriun.app.protocol

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PacketReaderTest {
    private fun frame(type: Int, body: ByteArray): ByteArray {
        val total = 4 + body.size
        val h = PacketBuilder.header(type, total)
        return byteArrayOf(h.toByte(), (h shr 8).toByte(), (h shr 16).toByte(), (h shr 24).toByte()) + body
    }

    /** Hands out one byte per read and raises a timeout between every byte, like a slow socket with SO_TIMEOUT. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var pos = 0
        private var timeoutNext = true

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            if (timeoutNext) {
                timeoutNext = false
                throw SocketTimeoutException()
            }
            timeoutNext = true
            b[off] = data[pos++]
            return 1
        }
    }

    @Test
    fun readsBackToBackPackets() {
        val stream = frame(MessageType.SYNC, byteArrayOf()) + frame(MessageType.ACK, byteArrayOf(1, 2, 3, 4))
        val reader = PacketReader(ByteArrayInputStream(stream))
        val a = reader.poll()!!
        assertEquals(MessageType.SYNC, a.type)
        assertEquals(4, a.length)
        val b = reader.poll()!!
        assertEquals(MessageType.ACK, b.type)
        assertEquals(8, b.length)
        assertEquals(1, b.data[4].toInt())
    }

    @Test
    fun partialPacketsSurviveReadTimeouts() {
        val bytes = frame(MessageType.CONTROL, ByteArray(8) { it.toByte() })
        val reader = PacketReader(Trickle(bytes))
        var packet: Packet? = null
        var polls = 0
        while (packet == null && polls < 1000) {
            packet = reader.poll()
            polls++
        }
        assertNotNull(packet)
        assertEquals(MessageType.CONTROL, packet!!.type)
        assertEquals(12, packet.length)
        assertTrue("needed timeouts, so more than one poll", polls > 1)
        assertEquals(7, packet.data[11].toInt())
    }

    @Test
    fun timeoutWithNoDataReturnsNull() {
        val reader = PacketReader(Trickle(frame(MessageType.SYNC, byteArrayOf())))
        assertNull(reader.poll())
    }

    @Test
    fun lengthBelowHeaderSizeIsAProtocolError() {
        val reader = PacketReader(ByteArrayInputStream(byteArrayOf(2, 0, 0, 4)))
        try {
            reader.poll()
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun lengthAboveTheLimitIsAProtocolError() {
        val h = PacketBuilder.header(MessageType.VIDEO, 0xFFFFFF)
        val reader = PacketReader(ByteArrayInputStream(byteArrayOf(h.toByte(), (h shr 8).toByte(), (h shr 16).toByte(), (h shr 24).toByte())), maxLength = 1000)
        try {
            reader.poll()
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun endOfStreamThrowsEof() {
        val reader = PacketReader(ByteArrayInputStream(byteArrayOf(8, 0, 0, 9, 1)))
        try {
            reader.poll()
            fail("expected EOFException")
        } catch (_: EOFException) {
        }
    }
}
