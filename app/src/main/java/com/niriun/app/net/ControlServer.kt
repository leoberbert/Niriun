package com.niriun.app.net

import com.niriun.app.protocol.Packet
import com.niriun.app.protocol.PacketReader
import com.niriun.app.protocol.Wire
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The TCP endpoint the PC connects to. One client at a time (backlog 1), like the reference.
 *
 * Threading: [Listener.onConnected], [Listener.onPacket] and [Listener.onDisconnected] run on the server
 * thread; [send] may be called from any thread.
 */
class ControlServer(
    private val port: Int,
    private val listener: Listener,
    private val idleTimeoutMs: Long = Wire.IDLE_TIMEOUT_MS,
    private val sendStallTimeoutMs: Long = Wire.SEND_STALL_TIMEOUT_MS,
) {
    interface Listener {
        fun onConnected(peer: String)
        fun onPacket(packet: Packet)
        fun onDisconnected()
    }

    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile private var client: Socket? = null
    @Volatile private var writeStartedNanos = 0L

    /** The bound port (useful when constructed with port 0). */
    val localPort: Int get() = serverSocket?.localPort ?: -1

    val isConnected: Boolean get() = client != null

    @Throws(IOException::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val ss = bindDualStack(port, backlog = 1)
            ss.soTimeout = ACCEPT_POLL_MS
            serverSocket = ss
            thread = Thread({ acceptLoop(ss) }, "niriun-control").also { it.start() }
        } catch (e: IOException) {
            running.set(false)
            throw e
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly(client)
        closeQuietly(serverSocket)
        thread?.join(2_000)
        thread = null
        serverSocket = null
    }

    /** Drops the current client, if any. */
    fun disconnectClient() = closeQuietly(client)

    /** Sends [length] bytes of [data]; false when there is no client or the write failed (client dropped). */
    fun send(data: ByteArray, length: Int = data.size): Boolean {
        val sock = client ?: return false
        synchronized(writeLock) {
            if (client !== sock) return false
            writeStartedNanos = System.nanoTime()
            try {
                sock.getOutputStream().write(data, 0, length)
                return true
            } catch (_: IOException) {
                closeQuietly(sock)
                return false
            } finally {
                writeStartedNanos = 0L
            }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (!running.get()) break
                Thread.sleep(100)
                continue
            }
            serve(sock)
        }
    }

    private fun serve(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            if (sock.sendBufferSize < SEND_BUFFER) sock.sendBufferSize = SEND_BUFFER
            sock.soTimeout = READ_POLL_MS
            val reader = PacketReader(sock.getInputStream())
            client = sock
            listener.onConnected(sock.inetAddress?.hostAddress ?: "unknown")
            var lastMessage = System.nanoTime()
            while (running.get() && client === sock) {
                val now = System.nanoTime()
                val writeStarted = writeStartedNanos
                if (writeStarted != 0L && (now - writeStarted) / 1_000_000 > sendStallTimeoutMs) break
                val packet = reader.poll()
                if (packet == null) {
                    if (idleTimeoutMs > 0 && (System.nanoTime() - lastMessage) / 1_000_000 > idleTimeoutMs) break
                    continue
                }
                lastMessage = System.nanoTime()
                listener.onPacket(packet)
            }
        } catch (_: IOException) {
            // EOF, reset, bad frame or watchdog close: all end the session the same way.
        } finally {
            // Close first: a writer stuck on this socket holds writeLock, and only the close aborts its write. Taking the
            // lock first left the phone waiting for the kernel to give up on a dead peer (about a minute), while the
            // next computer sat in the accept backlog with a connected socket and no hello.
            closeQuietly(sock)
            synchronized(writeLock) { client = null }
            listener.onDisconnected()
        }
    }

    companion object {
        private const val ACCEPT_POLL_MS = 100
        private const val READ_POLL_MS = 100
        private const val SEND_BUFFER = 0x7D000

        /** `[::]` is dual-stack on Android/Linux; falls back to the IPv4 wildcard when IPv6 is unavailable. */
        fun bindDualStack(port: Int, backlog: Int): ServerSocket {
            val ss = ServerSocket()
            ss.reuseAddress = true
            try {
                ss.bind(InetSocketAddress(InetAddress.getByName("::"), port), backlog)
            } catch (_: IOException) {
                closeQuietly(ss)
                val v4 = ServerSocket()
                v4.reuseAddress = true
                v4.bind(InetSocketAddress(port), backlog)
                return v4
            }
            return ss
        }

        fun closeQuietly(c: java.io.Closeable?) {
            try {
                c?.close()
            } catch (_: IOException) {
            }
        }
    }
}
