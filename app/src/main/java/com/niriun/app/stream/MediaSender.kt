package com.niriun.app.stream

import com.niriun.app.net.ControlServer
import com.niriun.app.protocol.FlowControl
import com.niriun.app.protocol.PacketBuilder
import com.niriun.app.protocol.Wire
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Origin of the stream timestamps: seconds since the last START / SYNC. */
class StreamClock {
    @Volatile private var originNanos = System.nanoTime()

    fun reset() {
        originNanos = System.nanoTime()
    }

    /** Float on purpose: the wire carries f32 and the ACK echoes the exact value. */
    fun seconds(): Float = ((System.nanoTime() - originNanos) / 1_000_000_000.0).toFloat()
}

/**
 * Counts frames handed to the encoder versus frames it has produced. A slow encoder would otherwise buffer many
 * frames inside its input queue (seconds of latency); the renderer skips frames while the backlog is full.
 *
 * Some encoders legitimately drop frames (rate control) without producing output, so a backlog that makes no
 * progress for [stallNanos] is considered stale and is trimmed instead of blocking the stream forever.
 */
class EncoderBacklog(
    private val limit: Int = 3,
    private val stallNanos: Long = 400_000_000L,
    private val now: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private var submitted = 0L
    private var completed = 0L
    private var lastProgress = now()

    fun reset() {
        synchronized(lock) {
            submitted = 0
            completed = 0
            lastProgress = now()
        }
    }

    fun onSubmitted() {
        synchronized(lock) {
            if (submitted == completed) lastProgress = now() // the age of the oldest pending frame starts here
            submitted++
        }
    }

    fun onCompleted() {
        synchronized(lock) {
            completed++
            lastProgress = now()
        }
    }

    fun isFull(): Boolean {
        synchronized(lock) {
            if (submitted - completed < limit) return false
            if (now() - lastProgress > stallNanos) {
                completed = submitted - (limit - 1)
                lastProgress = now()
                return false
            }
            return true
        }
    }
}

/** Wraps encoded media into video/audio packets and pushes them to the TCP client. */
class MediaSender(private val server: ControlServer, private val flow: FlowControl) {
    private var videoBuffer = ByteArray(256 * 1024)
    private var audioBuffer = ByteArray(Wire.MAX_AUDIO_PAYLOAD + 8)

    val videoFrames = AtomicLong()
    val videoBytes = AtomicLong()

    /** [data] must be positioned at the payload start with [length] bytes remaining. Call from one thread only. */
    fun sendVideo(presentationTimeUs: Long, data: ByteBuffer, length: Int): Boolean {
        val payload = minOf(length, Wire.MAX_VIDEO_PAYLOAD)
        if (videoBuffer.size < payload + 8) videoBuffer = ByteArray(maxOf(payload + 8, videoBuffer.size * 2))
        val timestamp = (presentationTimeUs / 1_000_000.0).toFloat()
        val total = PacketBuilder.putVideo(videoBuffer, timestamp, data, payload)
        val ok = server.send(videoBuffer, total)
        if (ok) {
            flow.onFrameSent(timestamp)
            videoFrames.incrementAndGet()
            videoBytes.addAndGet(total.toLong())
        }
        return ok
    }

    fun sendAudio(data: ByteBuffer, length: Int): Boolean {
        if (length <= 0 || length > Wire.MAX_AUDIO_PAYLOAD) return false
        val total = PacketBuilder.putAudio(audioBuffer, data, length)
        return server.send(audioBuffer, total)
    }
}
