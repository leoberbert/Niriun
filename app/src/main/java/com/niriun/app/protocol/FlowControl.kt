package com.niriun.app.protocol

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ACK-based back-pressure. Mirrors the reference's `sent` / `previous` / `ack` registers: at most about two
 * frames may be unacknowledged; beyond that the renderer waits briefly and then drops camera frames.
 */
class FlowControl {
    private val lock = ReentrantLock()
    private val acked = lock.newCondition()

    private var lastSent = 0f
    private var previousSent = 0f
    private var lastAck = -1f

    fun reset() = lock.withLock {
        lastSent = 0f
        previousSent = 0f
        lastAck = -1f
        acked.signalAll()
    }

    fun onFrameSent(timestamp: Float) = lock.withLock {
        previousSent = lastSent
        lastSent = timestamp
    }

    fun onAck(timestamp: Float) = lock.withLock {
        lastAck = timestamp
        acked.signalAll()
    }

    /** True while the peer has not confirmed the frame before the most recent one. */
    fun isBehind(): Boolean = lock.withLock { behindLocked() }

    /**
     * Waits up to [timeoutMs] for an ACK if the peer is behind, then reports whether it still is.
     * `true` means the caller should drop the camera frame.
     */
    fun awaitAckOrBehind(timeoutMs: Long = 100): Boolean = lock.withLock {
        if (behindLocked()) {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (behindLocked() && remaining > 0) remaining = acked.awaitNanos(remaining)
        }
        behindLocked()
    }

    private fun behindLocked(): Boolean {
        if (lastSent == 0f || lastAck < 0f) return false
        return lastAck < previousSent
    }
}
