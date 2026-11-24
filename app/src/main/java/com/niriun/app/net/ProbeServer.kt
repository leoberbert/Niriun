package com.niriun.app.net

import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The secondary TCP port (4697): on connect it answers with the 280-byte hello and then lets the peer
 * close. The PC uses it to confirm a device it learned about by IP/beacon.
 */
class ProbeServer(private val port: Int, private val hello: () -> ByteArray) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val ss = ControlServer.bindDualStack(port, backlog = 4)
            ss.soTimeout = 100
            serverSocket = ss
            thread = Thread({ loop(ss) }, "niriun-probe").also { it.start() }
        } catch (e: IOException) {
            running.set(false)
            throw e
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        ControlServer.closeQuietly(serverSocket)
        thread?.join(2_000)
        thread = null
        serverSocket = null
    }

    private fun loop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (!running.get()) break
                continue
            }
            try {
                sock.use {
                    it.tcpNoDelay = true
                    it.soTimeout = 1_000
                    it.getOutputStream().write(hello())
                    // Wait for the peer to hang up (or give up after the idle second).
                    val sink = ByteArray(64)
                    while (it.getInputStream().read(sink) >= 0) { /* discard */ }
                }
            } catch (_: IOException) {
            }
        }
    }
}
