package com.niriun.app.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowControlTest {
    @Test
    fun neverBehindBeforeTheFirstAck() {
        val f = FlowControl()
        f.onFrameSent(0f)
        f.onFrameSent(0.033f)
        f.onFrameSent(0.066f)
        assertFalse(f.isBehind())
    }

    @Test
    fun behindWhenTheFrameBeforeTheLastIsUnacknowledged() {
        val f = FlowControl()
        f.onFrameSent(0.033f)
        f.onFrameSent(0.066f)
        f.onFrameSent(0.100f)
        f.onAck(0.033f) // previous = 0.066 > ack
        assertTrue(f.isBehind())
        f.onAck(0.066f)
        assertFalse(f.isBehind())
    }

    @Test
    fun awaitReturnsOnceTheAckArrives() {
        val f = FlowControl()
        f.onFrameSent(0.033f)
        f.onFrameSent(0.066f)
        f.onFrameSent(0.100f)
        f.onAck(0.033f)
        val started = CountDownLatch(1)
        Thread {
            started.await()
            Thread.sleep(50)
            f.onAck(0.066f)
        }.start()
        started.countDown()
        val t0 = System.nanoTime()
        assertFalse(f.awaitAckOrBehind(timeoutMs = 2_000))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 1_500)
    }

    @Test
    fun awaitTimesOutAndReportsBehind() {
        val f = FlowControl()
        f.onFrameSent(0.033f)
        f.onFrameSent(0.066f)
        f.onFrameSent(0.100f)
        f.onAck(0.033f)
        assertTrue(f.awaitAckOrBehind(timeoutMs = 50))
    }

    @Test
    fun resetClearsAllState() {
        val f = FlowControl()
        f.onFrameSent(0.033f)
        f.onFrameSent(0.066f)
        f.onAck(0.001f)
        assertTrue(f.isBehind())
        f.reset()
        assertFalse(f.isBehind())
    }
}
