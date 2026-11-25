package com.niriun.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun packet(type: Int, build: (ByteBuffer) -> Unit, size: Int): Packet {
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(PacketBuilder.header(type, size))
        build(b)
        return Packet(type, b.array())
    }

    @Test
    fun headerPacksLengthInLow24BitsAndTypeInTopByte() {
        assertEquals(0x01000118, PacketBuilder.header(MessageType.HELLO, 0x118))
        assertEquals(0x08000010, PacketBuilder.header(MessageType.VIDEO, 16))
        assertEquals(0x0A00000C, PacketBuilder.header(MessageType.SIZES, 12))
    }

    @Test
    fun helloMatchesTheObservedLayout() {
        val id = ByteArray(16) { (it + 1).toByte() }
        val bytes = PacketBuilder.hello(id, "Pixel 9")
        assertEquals(Wire.HELLO_SIZE, bytes.size)
        val b = le(bytes)
        assertEquals(0x01000118, b.getInt(0))
        assertArrayEquals(id, bytes.copyOfRange(4, 20))
        assertEquals(2, b.getShort(20).toInt()) // device kind: Android
        assertEquals(2, b.getShort(22).toInt()) // protocol version
        assertEquals("Pixel 9", String(bytes, 24, 7, Charsets.UTF_8))
        assertEquals(0, bytes[24 + 7].toInt())
        assertEquals(24 + 256, bytes.size)
    }

    @Test
    fun helloNameIsCappedAtFieldSizeMinusTerminatorWithoutSplittingCodePoints() {
        val bytes = PacketBuilder.hello(ByteArray(16), "é".repeat(300))
        // 2 bytes per char -> 127 chars fit in 255 bytes; the last byte of the field stays NUL.
        assertEquals(254, bytes.drop(24).indexOfFirst { it.toInt() == 0 })
        assertEquals(0, bytes.last().toInt())
    }

    @Test
    fun cameraUsageReportsWhetherAnAppHasTheVirtualCameraOpen() {
        fun usage(version: Int, state: Int) = packet(MessageType.CAMERA_USAGE, { it.putInt(version); it.putInt(0); it.putInt(state) }, 16)
        assertEquals(CameraUsage(2, 0, true), CameraUsage.parse(usage(2, 2)))
        assertEquals(CameraUsage(2, 0, false), CameraUsage.parse(usage(2, 1)))
        assertNull("the Android reference ignores word 1 <= 1", CameraUsage.parse(usage(1, 2)))
    }

    @Test
    fun sizesEncodesFlagsCountAndFloatFps() {
        val bytes = PacketBuilder.sizes(listOf(VideoMode(640, 480, 30), VideoMode(1920, 1080, 60)), microphone = true)
        val b = le(bytes)
        assertEquals(12 + 24, bytes.size)
        assertEquals(PacketBuilder.header(MessageType.SIZES, 36), b.getInt(0))
        assertEquals(1, b.getInt(4)) // bit0: microphone present
        assertEquals(2, b.getInt(8))
        assertEquals(640, b.getInt(12))
        assertEquals(480, b.getInt(16))
        assertEquals(30f, b.getFloat(20), 0f)
        assertEquals(1920, b.getInt(24))
        assertEquals(60f, b.getFloat(32), 0f)
    }

    @Test
    fun camerasListsFixed256ByteNames() {
        val bytes = PacketBuilder.cameras(listOf("Back camera", "Front camera"), selected = 1)
        val b = le(bytes)
        assertEquals(12 + 2 * 256, bytes.size)
        assertEquals(2, b.getInt(4))
        assertEquals(1, b.getInt(8))
        assertEquals("Back camera", String(bytes, 12, 11))
        assertEquals("Front camera", String(bytes, 12 + 256, 12))
    }


    @Test
    fun videoPacketIsHeaderTimestampThenPayload() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val dst = ByteArray(8 + payload.size)
        val n = PacketBuilder.putVideo(dst, 1.5f, ByteBuffer.wrap(payload), payload.size)
        assertEquals(14, n)
        val b = le(dst)
        assertEquals(PacketBuilder.header(MessageType.VIDEO, 14), b.getInt(0))
        assertEquals(1.5f, b.getFloat(4), 0f)
        assertArrayEquals(payload, dst.copyOfRange(8, 14))
    }

    @Test
    fun stopScreenCarriesTheModeAfterTheHeader() {
        val b = le(PacketBuilder.stopScreen(2))
        assertEquals(PacketBuilder.header(MessageType.STOP_SCREEN, 8), b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals(0x31, MessageType.STOP_SCREEN)
    }

    @Test
    fun audioPacketHasAZeroWordBeforeTheOpusPayload() {
        val payload = byteArrayOf(1, 2, 3)
        val dst = ByteArray(8 + payload.size)
        PacketBuilder.putAudio(dst, ByteBuffer.wrap(payload), payload.size)
        val b = le(dst)
        assertEquals(PacketBuilder.header(MessageType.AUDIO, 11), b.getInt(0))
        assertEquals(0, b.getInt(4))
        assertArrayEquals(payload, dst.copyOfRange(8, 11))
    }

    @Test
    fun startRequestParsesCodecSizeAndFloatFps() {
        val p = packet(MessageType.START, { it.putInt(1).putInt(Wire.CODEC_HEVC).putInt(1920).putInt(1080).putFloat(60f) }, 24)
        val r = StartRequest.parse(p)
        assertNotNull(r)
        assertTrue(r!!.sendAudio)
        assertEquals("video/hevc", r.mime)
        assertEquals(1920, r.width)
        assertEquals(1080, r.height)
        assertEquals(60f, r.fps, 0f)
    }

    @Test
    fun startRequestDefaultsToH264() {
        val p = packet(MessageType.START, { it.putInt(0).putInt(Wire.CODEC_H264).putInt(640).putInt(480).putFloat(30f) }, 24)
        val r = StartRequest.parse(p)!!
        assertFalse(r.sendAudio)
        assertEquals("video/avc", r.mime)
    }

    @Test
    fun ackParsesOptionalDelays() {
        val short = packet(MessageType.ACK, { it.putFloat(2.25f) }, 8)
        assertEquals(Ack(2.25f, 0, 0), Ack.parse(short))
        val long = packet(MessageType.ACK, { it.putFloat(2.25f).putInt(7).putInt(9) }, 16)
        assertEquals(Ack(2.25f, 7, 9), Ack.parse(long))
    }

    @Test
    fun controlExposesRawIntAndFloatViews() {
        val zoom = Control.parse(packet(MessageType.CONTROL, { it.putInt(ControlId.ZOOM).putInt(40) }, 12))!!
        assertEquals(ControlId.ZOOM, zoom.id)
        assertEquals(40, zoom.raw)
        val ev = Control.parse(packet(MessageType.CONTROL, { it.putInt(ControlId.EXPOSURE_COMPENSATION).putFloat(-1.5f) }, 12))!!
        assertEquals(-1.5f, ev.asFloat, 0f)
    }

    @Test
    fun touchRejectsOutOfRangeAndReadsReleaseFlag() {
        assertNull(Touch.parse(packet(MessageType.TOUCH, { it.putFloat(1.5f).putFloat(0.5f) }, 12)))
        val t = Touch.parse(packet(MessageType.TOUCH, { it.putFloat(0.25f).putFloat(0.75f).putInt(1) }, 16))!!
        assertEquals(0.25f, t.x, 0f)
        assertTrue(t.release)
        assertFalse(Touch.parse(packet(MessageType.TOUCH, { it.putFloat(0.25f).putFloat(0.75f) }, 12))!!.release)
    }

    @Test
    fun manualFocusUsesBit0AsManualFlag() {
        val f = ManualFocus.parse(packet(MessageType.FOCUS, { it.putInt(1).putFloat(2.5f) }, 12))!!
        assertTrue(f.manual)
        assertEquals(2.5f, f.distance, 0f)
        assertFalse(ManualFocus.parse(packet(MessageType.FOCUS, { it.putInt(0).putFloat(0f) }, 12))!!.manual)
    }

    @Test
    fun parsersRejectTheWrongTypeOrTruncatedPackets() {
        assertNull(StartRequest.parse(packet(MessageType.ACK, { it.putFloat(0f) }, 8)))
        assertNull(StartRequest.parse(packet(MessageType.START, { it.putInt(1) }, 8)))
        assertNull(Control.parse(packet(MessageType.CONTROL, { it.putInt(1) }, 8)))
    }

    private fun tallyPacket(state: Int, name: ByteArray?, fieldSize: Int = Wire.NAME_FIELD_SIZE): Packet {
        val size = if (name == null) 8 else 8 + fieldSize
        return packet(MessageType.TALLY, { b ->
            b.putInt(state)
            if (name != null) b.put(name, 0, minOf(name.size, fieldSize))
        }, size)
    }

    @Test
    fun tallyMessageCarriesStateAndSourceName() {
        val t = TallyMessage.parse(tallyPacket(2, "Camera 1".toByteArray()))!!
        assertEquals(Tally.PROGRAM, t.state)
        assertEquals("Camera 1", t.source)
        assertEquals(Tally.PREVIEW, TallyMessage.parse(tallyPacket(1, ByteArray(0)))!!.state)
        assertEquals(Tally.OFF, TallyMessage.parse(tallyPacket(0, ByteArray(0)))!!.state)
    }

    @Test
    fun tallyMessageWithoutNameIsAccepted() {
        val t = TallyMessage.parse(tallyPacket(1, null))!!
        assertEquals(Tally.PREVIEW, t.state)
        assertEquals("", t.source)
    }

    @Test
    fun tallyNameFillingTheWholeFieldNeedsNoTerminator() {
        val name = "x".repeat(Wire.NAME_FIELD_SIZE)
        assertEquals(name, TallyMessage.parse(tallyPacket(2, name.toByteArray()))!!.source)
    }

    @Test
    fun tallyRejectsUnknownStateAndTruncatedPackets() {
        assertNull(TallyMessage.parse(tallyPacket(3, null)))
        assertNull(TallyMessage.parse(tallyPacket(-1, null)))
        assertNull(TallyMessage.parse(packet(MessageType.TALLY, { }, 4)))
        assertNull(TallyMessage.parse(packet(MessageType.TALLY, { it.putShort(2) }, 6)))
        assertNull(TallyMessage.parse(packet(MessageType.PEER_INFO, { it.putInt(2).putInt(0) }, 12)))
    }

    @Test
    fun tallyNameWithInvalidUtf8DoesNotThrow() {
        val odd = byteArrayOf(0xFF.toByte(), 0xC3.toByte(), 'a'.code.toByte())
        val t = TallyMessage.parse(tallyPacket(2, odd))!!
        assertEquals(Tally.PROGRAM, t.state)
        assertTrue(t.source.endsWith("a"))
        // A field shorter than the 256 B the contract asks for is still read up to the end of the packet.
        assertEquals("short", TallyMessage.parse(tallyPacket(1, "short".toByteArray(), fieldSize = 5))!!.source)
    }
}
