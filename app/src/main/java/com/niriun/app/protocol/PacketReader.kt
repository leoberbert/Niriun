package com.niriun.app.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

class ProtocolException(message: String) : IOException(message)

/**
 * Incremental framing reader. Partial packets survive read timeouts, so the caller can use a short
 * `SO_TIMEOUT` to run idle/stall checks between reads without losing bytes.
 */
class PacketReader(private val input: InputStream, private val maxLength: Int = Wire.MAX_INCOMING_PACKET) {
    private var buf = ByteArray(256)
    private var filled = 0
    private var expected = 0

    /** @return the next complete packet, or null when the read timed out first. EOF throws [EOFException]. */
    fun poll(): Packet? {
        if (expected == 0) {
            if (!fill(Wire.HEADER_SIZE)) return null
            val length = le32(0) and 0xFFFFFF
            if (length < Wire.HEADER_SIZE || length > maxLength) throw ProtocolException("invalid packet length $length")
            expected = length
            if (buf.size < length) buf = buf.copyOf(length)
        }
        if (!fill(expected)) return null
        val type = (le32(0) ushr 24) and 0xFF
        val packet = Packet(type, buf.copyOf(expected))
        filled = 0
        expected = 0
        return packet
    }

    private fun fill(target: Int): Boolean {
        while (filled < target) {
            val n = try {
                input.read(buf, filled, target - filled)
            } catch (_: SocketTimeoutException) {
                return false
            }
            if (n < 0) throw EOFException()
            filled += n
        }
        return true
    }

    private fun le32(o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or ((buf[o + 3].toInt() and 0xFF) shl 24)
}
