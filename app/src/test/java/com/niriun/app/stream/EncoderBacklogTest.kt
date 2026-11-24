package com.niriun.app.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderBacklogTest {
    private var nowNanos = 0L
    private fun backlog() = EncoderBacklog(limit = 3, stallNanos = 400_000_000L, now = { nowNanos })

    @Test
    fun fillsAtTheLimitAndDrainsAsOutputsArrive() {
        val b = backlog()
        repeat(2) { b.onSubmitted() }
        assertFalse(b.isFull())
        b.onSubmitted()
        assertTrue(b.isFull())
        b.onCompleted()
        assertFalse(b.isFull())
    }

    @Test
    fun anEncoderThatSilentlyDropsFramesDoesNotBlockTheStreamForever() {
        val b = backlog()
        repeat(3) { b.onSubmitted() }
        assertTrue(b.isFull())
        nowNanos += 500_000_000L // no output for longer than the stall window
        assertFalse(b.isFull())
        // The stale frames were written off down to limit-1 pending, so exactly one more frame is accepted.
        b.onSubmitted()
        assertTrue(b.isFull())
        b.onCompleted()
        assertFalse(b.isFull())
    }

    @Test
    fun resetForgetsPendingFrames() {
        val b = backlog()
        repeat(3) { b.onSubmitted() }
        b.reset()
        assertFalse(b.isFull())
    }
}
