package com.niriun.app.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.Surface
import com.niriun.app.net.BeaconSender
import com.niriun.app.net.ControlServer
import com.niriun.app.net.DeviceIdentity
import com.niriun.app.net.NsdAdvertiser
import com.niriun.app.net.ProbeServer
import com.niriun.app.obs.ObsClient
import com.niriun.app.obs.ObsConfig
import com.niriun.app.protocol.Ack
import com.niriun.app.protocol.Control
import com.niriun.app.protocol.ControlId
import com.niriun.app.protocol.FlowControl
import com.niriun.app.protocol.ManualFocus
import com.niriun.app.protocol.MessageType
import com.niriun.app.protocol.Packet
import com.niriun.app.protocol.PacketBuilder
import com.niriun.app.protocol.CameraUsage
import com.niriun.app.protocol.PeerInfo
import com.niriun.app.protocol.StartRequest
import com.niriun.app.protocol.Touch
import com.niriun.app.protocol.VideoMode
import com.niriun.app.protocol.Wire

/**
 * One running instance of the app's network + camera behaviour: discovery, the TCP control channel and the
 * camera/encoder pipeline that START requests switch on. Everything that touches camera/encoder state runs on
 * a single worker thread; only ACKs (flow control) are handled inline on the network thread.
 */
class StreamSession(
    private val context: Context,
    private val publish: ((UiState) -> UiState) -> Unit,
    private val onCloseRequested: () -> Unit,
) : ControlServer.Listener {
    private val settings = Settings(context)
    private val identity = DeviceIdentity.load(context)
    private val catalog = CameraCatalog(context)
    private val clock = StreamClock()
    private val flow = FlowControl()

    private val server = ControlServer(Wire.TCP_PORT, this)
    private val sender = MediaSender(server, flow)
    private val probe = ProbeServer(Wire.PROBE_PORT) { hello() }
    private val beacon = BeaconSender { hello() }
    private val nsd = NsdAdvertiser(context, identity)
    private val obs = ObsClient { s -> publish { it.copy(obs = s) } }
    private val backlog = EncoderBacklog()
    private val camera = CameraPipeline(context, clock, flow, backlog) { message -> post { fail(message) } }

    private val worker = HandlerThread("niriun-session").also { it.start() }
    private val handler = Handler(worker.looper)

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) post {
                camera.setDisplayRotation(currentDisplayRotation())
                applyOrientation()
            }
        }
    }

    private val micSupported = AudioStreamer.isSupported()

    // Worker-thread state.
    private var started = false

    /** Network side on (discoverable/serving). Off keeps the camera preview alive without any PC traffic. */
    private var broadcasting = false
    private val statsTick = Runnable { tickStats() }
    private var connected = false
    private var selectedIndex = 0
    private var mirror = settings.mirror
    private var torch = false
    private var previewSurface: Surface? = null
    private var activeRequest: StartRequest? = null
    private var encoder: VideoEncoder? = null
    private var encoderSurface: Surface? = null
    private var audio: AudioStreamer? = null
    private var lastFrames = 0L
    private var lastBytes = 0L

    // ---- lifecycle ----

    fun start() = post {
        if (started) return@post
        started = true
        selectedIndex = catalog.cameras.indexOfFirst { it.id == settings.cameraId }.coerceAtLeast(0)
        camera.setMirror(mirror)
        camera.setDisplayRotation(currentDisplayRotation())
        displayManager.registerDisplayListener(displayListener, handler)
        publish {
            UiState(
                phase = Phase.STOPPED,
                deviceName = identity.name,
                cameras = catalog.cameras.map(CameraInfo::name),
                selectedCamera = selectedIndex,
                microphoneAvailable = micSupported && hasPermission(Manifest.permission.RECORD_AUDIO),
                mirror = mirror,
                torchAvailable = catalog.cameras.getOrNull(selectedIndex)?.hasFlash == true,
                obs = it.obs,
                obsHost = settings.obsHost,
                obsSource = settings.obsSource,
            )
        }
        // The tally link starts with the app (not with Start) so we already know if this source is ON AIR.
        configureObs()
    }

    /** Start/stop the network side (discovery, TCP server, OBS link) while the camera preview keeps running. */
    fun setBroadcasting(enabled: Boolean) = post { if (enabled) startBroadcast() else stopBroadcast() }

    private fun startBroadcast() {
        if (!started || broadcasting) return
        try {
            server.start()
            runCatching { probe.start() }
            beacon.start()
            nsd.start()
        } catch (e: Exception) {
            server.stop()
            publish { it.copy(phase = Phase.STOPPED, error = "Cannot listen on port ${Wire.TCP_PORT}: ${e.message}") }
            return
        }
        broadcasting = true
        publish { it.copy(phase = Phase.WAITING, error = null) }
        handler.removeCallbacks(statsTick)
        handler.postDelayed(statsTick, 1_000)
    }

    private fun stopBroadcast() {
        if (!broadcasting) return
        broadcasting = false
        handler.removeCallbacks(statsTick)
        stopStreaming(releaseCamera = false)
        beacon.stop()
        nsd.stop()
        probe.stop()
        server.stop()
        connected = false
        publish { it.copy(phase = Phase.STOPPED, peer = null, streamInfo = null, audioActive = false, fps = 0f, kbps = 0) }
        ensureCamera()
        scheduleIdleExit()
    }

    /** With the network side off and no preview on screen there is nothing left to do: end the service. */
    private fun scheduleIdleExit() {
        handler.postDelayed({ if (started && !broadcasting && previewSurface == null) onCloseRequested() }, 3_000)
    }

    /** Blocks until everything is released. */
    fun stop() {
        val done = java.util.concurrent.CountDownLatch(1)
        post {
            started = false
            broadcasting = false
            handler.removeCallbacksAndMessages(null)
            stopStreaming()
            displayManager.unregisterDisplayListener(displayListener)
            obs.release()
            beacon.stop()
            nsd.stop()
            probe.stop()
            server.stop()
            camera.release()
            publish { UiState(phase = Phase.STOPPED) }
            done.countDown()
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        worker.quitSafely()
    }

    /** Re-reads the OBS settings and reconnects (called when the user saves them). */
    fun reloadObs() = post { configureObs() }

    fun selectObsSource(name: String) = post {
        settings.obsSource = name
        obs.setSource(name)
        publish { it.copy(obsSource = name) }
    }

    /** Adds a camera source to OBS and starts watching it. */
    fun createObsSource() = post {
        obs.createCameraSource("Niriun") { name -> selectObsSource(name) }
    }

    fun forgetObs() = post {
        settings.obsHost = ""
        settings.obsSource = ""
        configureObs()
    }

    private fun configureObs() {
        obs.start(ObsConfig(settings.obsHost, settings.obsPort, settings.obsPassword, settings.obsSource))
        publish { it.copy(obsHost = settings.obsHost, obsSource = settings.obsSource) }
    }

    // ---- UI entry points ----

    fun setPreviewSurface(surface: Surface?) = post {
        previewSurface = surface
        camera.setPreviewSurface(surface)
        ensureCamera()
        if (surface == null) scheduleIdleExit()
    }

    fun selectCamera(index: Int) = post { switchCamera(index) }

    fun setTorch(enabled: Boolean) = post { applyTorch(enabled) }

    fun setMirror(enabled: Boolean) = post { applyMirror(enabled) }

    fun setZoom(percent: Int) = post { camera.setZoomPercent(percent) }

    // ---- ControlServer.Listener (network thread) ----

    override fun onConnected(peer: String) {
        post { handleConnected(peer) }
    }

    override fun onPacket(packet: Packet) {
        if (packet.type == MessageType.ACK) {
            Ack.parse(packet)?.let { flow.onAck(it.timestamp) }
            return
        }
        post { handlePacket(packet) }
    }

    override fun onDisconnected() {
        post { handleDisconnected() }
    }

    // ---- worker thread ----

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    private fun handleConnected(peer: String) {
        connected = true
        beacon.stop()
        // Same order as the reference: hello, sizes, cameras.
        server.send(hello())
        sendModesAndCameras()
        publish { it.copy(phase = Phase.CONNECTED, peer = peer, error = null) }
    }

    private fun handleDisconnected() {
        connected = false
        stopStreaming()
        if (broadcasting) beacon.start()
        publish { it.copy(phase = if (broadcasting) Phase.WAITING else Phase.STOPPED, peer = null, streamInfo = null, audioActive = false, fps = 0f, kbps = 0, pcCameraInUse = null) }
    }

    private fun handlePacket(p: Packet) {
        when (p.type) {
            // The reference resets the clock origin here; doing it mid-stream would make encoder timestamps jump
            // backwards (GraphicBufferSource then drops every frame), so only honour it while idle.
            MessageType.SYNC -> if (activeRequest == null) clock.reset()
            MessageType.PEER_INFO -> PeerInfo.parse(p)
            MessageType.CAMERA_USAGE -> CameraUsage.parse(p)?.let { usage -> publish { it.copy(pcCameraInUse = usage.inUse) } }
            MessageType.START -> StartRequest.parse(p)?.let(::handleStart)
            MessageType.CONTROL -> Control.parse(p)?.let(::handleControl)
            MessageType.TOUCH -> Touch.parse(p)?.let { camera.tapToFocus(it.x, it.y, it.release) }
            MessageType.FOCUS -> ManualFocus.parse(p)?.let { camera.setManualFocus(it.manual, it.distance) }
            else -> Unit // HELLO echo, DIAGNOSTIC and unknown types are ignored, as in the reference.
        }
    }

    private fun handleStart(req: StartRequest) {
        stopStreaming(releaseCamera = false)
        val cam = catalog.cameras.getOrNull(selectedIndex)
        if (cam == null || !hasPermission(Manifest.permission.CAMERA)) {
            fail("Camera unavailable", dropPeer = true)
            return
        }
        if (req.width !in 16..8192 || req.height !in 16..8192) {
            fail("Unsupported size ${req.width}x${req.height}", dropPeer = true)
            return
        }
        clock.reset()
        flow.reset()
        backlog.reset()
        activeRequest = req
        val fps = req.fps.toInt().coerceIn(1, 120)
        camera.open(cam, catalog.bestSize(cam, maxOf(req.width, req.height), minOf(req.width, req.height)), fps)
        camera.setTorch(torch)
        if (!startEncoder(req)) return

        var audioOn = false
        if (req.sendAudio && micSupported && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            audio = AudioStreamer(sender) { message -> post { audioFailed(message) } }.also { it.start() }
            audioOn = true
        }
        val codecName = if (req.codec == Wire.CODEC_HEVC) "H.265" else "H.264"
        publish {
            it.copy(
                phase = Phase.STREAMING,
                streamInfo = "${encoderWidth}x$encoderHeight @ $fps fps · $codecName",
                audioActive = audioOn,
                error = null,
            )
        }
    }

    private fun stopStreaming(releaseCamera: Boolean = true) {
        audio?.stop()
        audio = null
        if (encoder != null || encoderSurface != null) {
            camera.setEncoderSurface(null)
            encoder?.stop()
            encoderSurface?.release()
        }
        encoder = null
        encoderSurface = null
        activeRequest = null
        if (releaseCamera) ensureCamera()
        if (broadcasting) {
            publish { it.copy(phase = if (connected) Phase.CONNECTED else Phase.WAITING, streamInfo = null, audioActive = false, fps = 0f, kbps = 0) }
        }
    }

    /** Preview-only camera: open while a preview surface is attached and nothing is streaming. */
    private fun ensureCamera() {
        if (activeRequest != null) return
        val cam = catalog.cameras.getOrNull(selectedIndex)
        if (previewSurface != null && cam != null && hasPermission(Manifest.permission.CAMERA)) {
            camera.open(cam, catalog.previewSize(cam), 30)
        } else {
            camera.close()
        }
    }

    private fun switchCamera(index: Int) {
        val cam = catalog.cameras.getOrNull(index) ?: return
        selectedIndex = index
        torch = false
        settings.cameraId = cam.id
        val req = activeRequest
        if (req != null) {
            camera.open(cam, catalog.bestSize(cam, maxOf(req.width, req.height), minOf(req.width, req.height)), req.fps.toInt().coerceIn(1, 120))
        } else {
            ensureCamera()
        }
        if (connected) sendModesAndCameras()
        publish { it.copy(selectedCamera = index, torch = false, torchAvailable = cam.hasFlash) }
    }

    private fun applyMirror(enabled: Boolean) {
        mirror = enabled
        settings.mirror = enabled
        camera.setMirror(enabled)
        publish { it.copy(mirror = enabled) }
    }

    private fun handleControl(c: Control) {
        when (c.id) {
            ControlId.LOCK_EXPOSURE_FOCUS -> camera.toggleLock()
            ControlId.TORCH -> applyTorch(!torch)
            ControlId.MIRROR -> applyMirror(!mirror)
            ControlId.ZOOM -> camera.setZoomPercent(c.raw)
            ControlId.NEXT_CAMERA -> if (catalog.cameras.size > 1) switchCamera((selectedIndex + 1) % catalog.cameras.size)
            ControlId.SELECT_CAMERA -> switchCamera(c.raw)
            ControlId.ISO -> camera.setIso(c.raw)
            ControlId.EXPOSURE_TIME -> {
                val seconds = c.asFloat
                camera.setExposureTimeNanos(if (seconds < 0f) -1L else (seconds * 1e9).toLong())
            }
            ControlId.EXPOSURE_COMPENSATION -> camera.setExposureCompensation(c.asFloat)
            ControlId.WHITE_BALANCE -> camera.setWhiteBalance(c.raw)
            ControlId.CLOSE_APP -> onCloseRequested()
        }
    }

    private fun applyTorch(enabled: Boolean) {
        torch = enabled && catalog.cameras.getOrNull(selectedIndex)?.hasFlash == true
        camera.setTorch(torch)
        publish { it.copy(torch = torch) }
    }

    private fun sendModesAndCameras() {
        val cam = catalog.cameras.getOrNull(selectedIndex)
        val modes: List<VideoMode> = cam?.let(catalog::modes).orEmpty()
        val mic = micSupported && hasPermission(Manifest.permission.RECORD_AUDIO)
        server.send(PacketBuilder.sizes(modes, mic))
        server.send(PacketBuilder.cameras(catalog.cameras.map(CameraInfo::name), selectedIndex))
    }

    private fun tickStats() {
        if (!broadcasting) return
        val frames = sender.videoFrames.get()
        val bytes = sender.videoBytes.get()
        val fps = (frames - lastFrames).toFloat()
        val kbps = ((bytes - lastBytes) * 8 / 1000).toInt()
        lastFrames = frames
        lastBytes = bytes
        if (activeRequest != null) publish { it.copy(fps = fps, kbps = kbps) }
        handler.postDelayed(statsTick, 1_000)
    }

    private fun fail(message: String, dropPeer: Boolean = activeRequest != null) {
        publish { it.copy(error = message) }
        if (activeRequest != null) stopStreaming()
        // A dead camera/encoder would leave the PC waiting forever: drop the connection so it notices.
        if (dropPeer) server.disconnectClient()
        if (activeRequest == null) ensureCamera()
    }

    private fun audioFailed(message: String) {
        audio?.stop()
        audio = null
        publish { it.copy(error = message, audioActive = false) }
    }

    private fun hello(): ByteArray = PacketBuilder.hello(identity.id, identity.name)

    private fun hasPermission(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isPortrait(): Boolean {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        return p.y > p.x
    }

    /** The stream follows the screen: portrait phones send portrait frames, landscape phones landscape ones. */
    private fun orientedSize(req: StartRequest): Pair<Int, Int> {
        val long = maxOf(req.width, req.height)
        val short = minOf(req.width, req.height)
        return if (isPortrait()) short to long else long to short
    }

    private var encoderWidth = 0
    private var encoderHeight = 0

    /** Starts the encoder for [req] at the current orientation and attaches it to the renderer. */
    private fun startEncoder(req: StartRequest): Boolean {
        val (w, h) = orientedSize(req)
        val enc = VideoEncoder(req.copy(width = w, height = h), sender, backlog) { message -> post { fail(message) } }
        val surface = try {
            enc.start()
        } catch (e: Exception) {
            fail("Encoder failed: ${e.message}", dropPeer = true)
            return false
        }
        encoder = enc
        encoderSurface = surface
        encoderWidth = w
        encoderHeight = h
        camera.setEncoderSurface(surface)
        return true
    }

    private fun applyOrientation() {
        val req = activeRequest ?: return
        val (w, h) = orientedSize(req)
        if (w == encoderWidth && h == encoderHeight) return
        // New resolution: restart the encoder; the decoder picks the new SPS/PPS up at the next IDR.
        camera.setEncoderSurface(null)
        encoder?.stop()
        encoderSurface?.release()
        encoder = null
        encoderSurface = null
        backlog.reset()
        if (!startEncoder(req)) return
        val fps = req.fps.toInt().coerceIn(1, 120)
        val codecName = if (req.codec == Wire.CODEC_HEVC) "H.265" else "H.264"
        publish { it.copy(streamInfo = "${encoderWidth}x$encoderHeight @ $fps fps · $codecName") }
    }

    private fun currentDisplayRotation(): Int =
        when (displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
}
