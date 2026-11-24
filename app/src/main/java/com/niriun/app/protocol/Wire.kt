package com.niriun.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Constants of the wire protocol. Layout and evidence: docs/PROTOCOL.md. */
object Wire {
    const val TCP_PORT = 4699
    const val PROBE_PORT = 4697
    /**
     * UDP discovery port. Ghidra shows the sockaddr constant `0x5A12` in the APK, which is the port already in
     * network byte order (bytes 12 5A); the real port is 0x125A = 4698 (engine.dll binds UDP 0.0.0.0:4698).
     */
    const val BEACON_PORT = 4698
    const val BEACON_GROUP_V4 = "239.255.188.58"
    const val BEACON_GROUP_V6 = "ff02::2:d644"
    const val NSD_SERVICE_TYPE = "_iriunwebcam._tcp."

    const val HEADER_SIZE = 4
    const val NAME_FIELD_SIZE = 256
    const val HELLO_SIZE = 0x118
    const val DEVICE_KIND_ANDROID = 2
    const val PROTOCOL_VERSION = 2

    /** The receiver of the reference implementation drops connections that announce more. */
    const val MAX_INCOMING_PACKET = 4_000_000
    const val MAX_VIDEO_PAYLOAD = 0x4C4B38
    const val MAX_AUDIO_PAYLOAD = 0x1380

    const val IDLE_TIMEOUT_MS = 10_000L
    const val SEND_STALL_TIMEOUT_MS = 4_000L
    const val BEACON_INTERVAL_MS = 2_000L

    const val CODEC_H264 = 1
    const val CODEC_HEVC = 2
}

object MessageType {
    const val HELLO = 0x01
    const val SYNC = 0x04
    const val AUDIO = 0x07
    const val VIDEO = 0x08
    const val ACK = 0x09
    const val SIZES = 0x0A
    const val START = 0x0B
    const val DIAGNOSTIC = 0x0C
    const val CONTROL = 0x10
    const val PEER_INFO = 0x11
    const val TOUCH = 0x12
    const val CAMERAS = 0x18
    const val CAMERA_USAGE = 0x1A
    const val FOCUS = 0x19
}

/** `id` values of the CONTROL message. */
object ControlId {
    const val LOCK_EXPOSURE_FOCUS = 1
    const val TORCH = 2
    const val MIRROR = 3
    const val ZOOM = 4
    const val NEXT_CAMERA = 5
    const val ISO = 6
    const val EXPOSURE_TIME = 7
    const val EXPOSURE_COMPENSATION = 8
    const val WHITE_BALANCE = 9
    const val CLOSE_APP = 11
    const val SELECT_CAMERA = 14
}

data class VideoMode(val width: Int, val height: Int, val fps: Int)

/** A decoded incoming packet: [data] holds the whole packet, header included. */
class Packet(val type: Int, val data: ByteArray) {
    val length: Int get() = data.size

    fun has(offset: Int, size: Int) = offset + size <= data.size

    fun u32(offset: Int): Int = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
    fun f32(offset: Int): Float = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat(offset)
    fun u64(offset: Int): Long = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong(offset)
}

data class StartRequest(val sendAudio: Boolean, val codec: Int, val width: Int, val height: Int, val fps: Float) {
    val mime: String get() = if (codec == Wire.CODEC_HEVC) "video/hevc" else "video/avc"

    companion object {
        fun parse(p: Packet): StartRequest? {
            if (p.type != MessageType.START || !p.has(0, 20)) return null
            return StartRequest(
                sendAudio = p.u32(4) != 0,
                codec = p.u32(8),
                width = p.u32(12),
                height = p.u32(16),
                // Older clients may omit the fps field; the reference reads it unconditionally.
                fps = if (p.has(20, 4)) p.f32(20) else 30f,
            )
        }
    }
}

data class Ack(val timestamp: Float, val decodeDelay: Int, val postProcessDelay: Int) {
    companion object {
        fun parse(p: Packet): Ack? {
            if (p.type != MessageType.ACK || !p.has(0, 8)) return null
            return Ack(
                timestamp = p.f32(4),
                decodeDelay = if (p.has(8, 4) && p.length >= 16) p.u32(8) else 0,
                postProcessDelay = if (p.has(12, 4) && p.length >= 16) p.u32(12) else 0,
            )
        }
    }
}

/** CONTROL message: [raw] is the integer view of the value, [asFloat] the float view. */
data class Control(val id: Int, val raw: Int) {
    val asFloat: Float get() = java.lang.Float.intBitsToFloat(raw)

    companion object {
        fun parse(p: Packet): Control? {
            if (p.type != MessageType.CONTROL || !p.has(0, 12)) return null
            return Control(p.u32(4), p.u32(8))
        }
    }
}

data class Touch(val x: Float, val y: Float, val release: Boolean) {
    companion object {
        fun parse(p: Packet): Touch? {
            if (p.type != MessageType.TOUCH || !p.has(0, 12)) return null
            val x = p.f32(4)
            val y = p.f32(8)
            if (!(x in 0f..1f) || !(y in 0f..1f)) return null
            return Touch(x, y, release = p.length >= 13 && p.has(12, 4) && p.u32(12) == 1)
        }
    }
}

data class ManualFocus(val manual: Boolean, val distance: Float) {
    companion object {
        fun parse(p: Packet): ManualFocus? {
            if (p.type != MessageType.FOCUS || !p.has(0, 12)) return null
            return ManualFocus(manual = (p.u32(4) and 1) != 0, distance = p.f32(8))
        }
    }
}

/**
 * Type 0x1A, sent by the PC after START and whenever it changes: [inUse] is true when an application has the virtual
 * camera open (wire value 2; 1 means nobody does; engine.dll `FUN_10017940` + 1).
 */
data class CameraUsage(val version: Int, val camera: Int, val inUse: Boolean) {
    companion object {
        fun parse(p: Packet): CameraUsage? {
            // The Android reference ignores the message unless word 1 is > 1.
            if (p.type != MessageType.CAMERA_USAGE || !p.has(0, 16) || p.u32(4) <= 1) return null
            return CameraUsage(p.u32(4), p.u32(8), p.u32(12) == 2)
        }
    }
}

data class PeerInfo(val kind: Int, val version: Int) {
    companion object {
        fun parse(p: Packet): PeerInfo? {
            if (p.type != MessageType.PEER_INFO || !p.has(0, 12)) return null
            return PeerInfo(p.u32(4), p.u32(8))
        }
    }
}

/** Builders for the packets the device sends. All integers are little-endian. */
object PacketBuilder {
    fun header(type: Int, totalLength: Int): Int = (totalLength and 0xFFFFFF) or (type shl 24)

    private fun newPacket(type: Int, totalLength: Int): ByteBuffer =
        ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN).also { it.putInt(header(type, totalLength)) }

    private fun ByteBuffer.putName(name: String) {
        val start = position()
        val bytes = truncateUtf8(name, Wire.NAME_FIELD_SIZE - 1)
        put(bytes)
        position(start + Wire.NAME_FIELD_SIZE)
    }

    /** Cuts to at most [maxBytes] without splitting a code point. */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chunk = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + chunk.size > maxBytes) break
            out.write(chunk)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }

    /** Type 0x01, 280 bytes. Also used as the multicast beacon and the 4697 probe reply. */
    fun hello(deviceId: ByteArray, deviceName: String): ByteArray {
        require(deviceId.size == 16) { "device id must have 16 bytes" }
        val b = newPacket(MessageType.HELLO, Wire.HELLO_SIZE)
        b.put(deviceId)
        b.putShort(Wire.DEVICE_KIND_ANDROID.toShort())
        b.putShort(Wire.PROTOCOL_VERSION.toShort())
        b.putName(deviceName)
        return b.array()
    }

    /** Type 0x0A. The first mode is the PC's default selection. */
    fun sizes(modes: List<VideoMode>, microphone: Boolean): ByteArray {
        val b = newPacket(MessageType.SIZES, 12 + 12 * modes.size)
        b.putInt(if (microphone) 1 else 0)
        b.putInt(modes.size)
        for (m in modes) {
            b.putInt(m.width)
            b.putInt(m.height)
            b.putFloat(m.fps.toFloat())
        }
        return b.array()
    }

    /** Type 0x18. */
    fun cameras(names: List<String>, selected: Int): ByteArray {
        val b = newPacket(MessageType.CAMERAS, 12 + Wire.NAME_FIELD_SIZE * names.size)
        b.putInt(names.size)
        b.putInt(selected)
        for (n in names) b.putName(n)
        return b.array()
    }

    /** Writes a type 0x08 packet into [dst] and returns its length. [dst] must hold `8 + length` bytes. */
    fun putVideo(dst: ByteArray, timestampSeconds: Float, src: ByteBuffer, length: Int): Int {
        val total = 8 + length
        val b = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(header(MessageType.VIDEO, total))
        b.putFloat(timestampSeconds)
        src.get(dst, 8, length)
        return total
    }

    /** Writes a type 0x07 packet into [dst] and returns its length. */
    fun putAudio(dst: ByteArray, src: ByteBuffer, length: Int): Int {
        val total = 8 + length
        val b = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(header(MessageType.AUDIO, total))
        b.putInt(0)
        src.get(dst, 8, length)
        return total
    }
}
