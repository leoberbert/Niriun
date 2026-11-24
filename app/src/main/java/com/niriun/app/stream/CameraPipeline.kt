package com.niriun.app.stream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import com.niriun.app.protocol.FlowControl
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Camera2 session feeding a [GlRenderer]. The camera only ever targets the renderer's SurfaceTexture, so
 * attaching/detaching the preview or the encoder never reconfigures the capture session.
 */
class CameraPipeline(
    context: Context,
    private val clock: StreamClock,
    private val flow: FlowControl,
    private val backlog: EncoderBacklog,
    private val reportError: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("niriun-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    private var generation = 0
    private var info: CameraInfo? = null
    private var characteristics: CameraCharacteristics? = null
    private var bufferSize: Size? = null
    private var fps = 30
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var renderer: GlRenderer? = null

    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var displayRotation = 0
    private var mirror = false

    private var zoomPercent = 0
    private var torch = false
    private var evStops = 0f
    private var locked = false
    private var whiteBalanceKelvin = -1
    private var iso = -1
    private var exposureNanos = -1L
    private var manualFocusDistance: Float? = null
    private var meteringRect: Rect? = null

    val isOpen: Boolean get() = synchronized(lock) { renderer != null }

    /** (Re)opens [camera] when the camera, buffer size or fps differ from the running session. */
    @SuppressLint("MissingPermission")
    fun open(camera: CameraInfo, size: Size, fps: Int) {
        synchronized(lock) { openLocked(camera, size, fps) }
    }

    @SuppressLint("MissingPermission")
    private fun openLocked(camera: CameraInfo, size: Size, fps: Int) {
        if (info?.id == camera.id && bufferSize == size && this.fps == fps && renderer != null) return
        closeLocked()
        val gen = ++generation
        info = camera
        bufferSize = size
        this.fps = fps
        resetCameraBoundControls()
        try {
            characteristics = manager.getCameraCharacteristics(camera.id)
            val sideways = camera.sensorOrientation % 180 != 0
            val contentW = if (sideways) size.height else size.width
            val contentH = if (sideways) size.width else size.height
            val gl = GlRenderer(
                size.width, size.height, contentW, contentH, clock,
                { flow.awaitAckOrBehind() || backlog.isFull() }, backlog::onSubmitted,
            )
            val surface = try {
                gl.start()
            } catch (t: Throwable) {
                gl.release()
                throw t
            }
            renderer = gl
            gl.setRotation(renderRotation())
            gl.setMirror(mirror)
            previewSurface?.let { gl.setPreviewSurface(it) }
            encoderSurface?.let { gl.setEncoderSurface(it) }
            manager.openCamera(camera.id, deviceCallback(gen, surface), handler)
        } catch (e: Exception) {
            closeLocked()
            reportError("Cannot open camera: ${e.message}")
        }
    }

    fun close() = synchronized(lock) { closeLocked() }

    fun release() {
        close()
        thread.quitSafely()
    }

    fun setPreviewSurface(surface: Surface?) = synchronized(lock) {
        previewSurface = surface
        renderer?.setPreviewSurface(surface)
    }

    fun setEncoderSurface(surface: Surface?) = synchronized(lock) {
        encoderSurface = surface
        renderer?.setEncoderSurface(surface)
    }

    fun setDisplayRotation(degrees: Int) = synchronized(lock) {
        displayRotation = degrees
        renderer?.setRotation(renderRotation())
    }

    fun setMirror(enabled: Boolean) = synchronized(lock) {
        mirror = enabled
        renderer?.setMirror(enabled)
    }

    // ---- remote controls (docs/PROTOCOL.md §4.3) ----

    fun setZoomPercent(percent: Int) = update { zoomPercent = percent.coerceIn(0, 100) }

    fun setTorch(enabled: Boolean) = update { if (info?.hasFlash == true) torch = enabled }

    fun toggleTorch() = update { if (info?.hasFlash == true) torch = !torch }

    fun toggleLock() = update { locked = !locked }

    fun setExposureCompensation(stops: Float) = update { evStops = stops }

    /** Kelvin > 0 selects manual white balance; <= 0 returns to auto. */
    fun setWhiteBalance(kelvin: Int) = update { whiteBalanceKelvin = kelvin }

    fun setIso(value: Int) = update { iso = value }

    fun setExposureTimeNanos(value: Long) = update { exposureNanos = value }

    fun setManualFocus(manual: Boolean, distance: Float) = update { manualFocusDistance = if (manual) distance else null }

    /** [x],[y] in 0..1 over the displayed (rotated) image; [release] returns to continuous AF. */
    fun tapToFocus(x: Float, y: Float, release: Boolean) {
        synchronized(lock) {
            val c = characteristics ?: return
            val b = builder ?: return
            val s = session ?: return
            if (release) {
                meteringRect = null
                applyLocked()
                return
            }
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val crop = effectiveCrop(c, active)
            // Undo the mirror first (it was applied last when drawing), then the clockwise rotation.
            val u = if (mirror) 1f - x else x
            val (bx, by) = when (computeRotation()) {
                90 -> y to (1f - u)
                180 -> (1f - u) to (1f - y)
                270 -> (1f - y) to u
                else -> u to y
            }
            val cx = crop.left + (bx * crop.width()).toInt()
            val cy = crop.top + (by * crop.height()).toInt()
            val half = max(crop.width(), crop.height()) / 16
            val rect = Rect(cx - half, cy - half, cx + half, cy + half)
            rect.offset(max(0, active.left - rect.left), max(0, active.top - rect.top))
            rect.offset(min(0, active.right - rect.right), min(0, active.bottom - rect.bottom))
            meteringRect = rect
            applyLocked()
            try {
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            } catch (_: Exception) {
                // The repeating request already carries the regions; the trigger is best-effort.
            }
        }
    }

    // ---- internals ----

    private inline fun update(block: () -> Unit) = synchronized(lock) {
        block()
        applyLocked()
    }

    private fun resetCameraBoundControls() {
        zoomPercent = 0
        torch = false
        evStops = 0f
        locked = false
        whiteBalanceKelvin = -1
        iso = -1
        exposureNanos = -1L
        manualFocusDistance = null
        meteringRect = null
    }

    private fun closeLocked() {
        generation++
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        builder = null
        renderer?.release()
        renderer = null
        characteristics = null
    }

    private fun deviceCallback(gen: Int, cameraSurface: Surface) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            synchronized(lock) {
                if (gen != generation) {
                    camera.close()
                    return
                }
                device = camera
                try {
                    val config = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(cameraSurface)),
                        { handler.post(it) },
                        sessionCallback(gen, camera, cameraSurface),
                    )
                    camera.createCaptureSession(config)
                } catch (e: Exception) {
                    reportError("Camera session failed: ${e.message}")
                }
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            synchronized(lock) {
                if (gen == generation) {
                    device = null
                    session = null
                }
            }
            if (gen == generation) reportError("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            synchronized(lock) {
                if (gen == generation) {
                    device = null
                    session = null
                }
            }
            if (gen == generation) reportError("Camera error $error")
        }
    }

    private fun sessionCallback(gen: Int, camera: CameraDevice, cameraSurface: Surface) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            synchronized(lock) {
                if (gen != generation) {
                    s.close()
                    return
                }
                session = s
                try {
                    builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).also { it.addTarget(cameraSurface) }
                    applyLocked()
                } catch (e: Exception) {
                    reportError("Camera request failed: ${e.message}")
                }
            }
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            if (gen == generation) reportError("Camera session configuration failed")
        }
    }

    /** Pushes every control into the request builder and restarts the repeating request. */
    private fun applyLocked() {
        val b = builder ?: return
        val s = session ?: return
        val c = characteristics ?: return
        try {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            // Android 12+ may rotate/crop the camera output itself (AUTO) when the device orientation changes. That
            // double-rotates the picture and distorts it, because the renderer already compensates; turn it off.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                c.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                    ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true
            ) {
                b.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
            }

            // Focus
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val manual = manualFocusDistance
            if (manual != null && minFocus > 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manual.coerceIn(0f, minFocus))
            } else if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            } else if (CaptureRequest.CONTROL_AF_MODE_AUTO in afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }

            // Exposure
            val aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList().orEmpty()
            val manualExposure = (iso >= 0 || exposureNanos >= 0) && CaptureRequest.CONTROL_AE_MODE_OFF in aeModes
            if (manualExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val timeRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val wantedIso = if (iso >= 0) iso else 400
                val wantedTime = if (exposureNanos >= 0) exposureNanos else 1_000_000_000L / 60
                b.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange?.let { wantedIso.coerceIn(it.lower, it.upper) } ?: wantedIso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, timeRange?.let { wantedTime.coerceIn(it.lower, it.upper) } ?: wantedTime)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(1_000_000_000L / fps, wantedTime))
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 3)
                if (range != null && step.toFloat() > 0f) {
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (evStops / step.toFloat()).roundToInt().coerceIn(range.lower, range.upper))
                }
                // AE may lower the frame rate in dim light when the range is variable (e.g. [15,60] or [30,60]).
                // Prefer a range that ends at the requested rate and starts as high as possible ([60,60] if offered).
                if (fps > 30) {
                    c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.filter { it.upper >= fps }
                        ?.sortedWith(compareBy({ it.upper - fps }, { fps - it.lower }))
                        ?.firstOrNull()
                        ?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
            }
            b.set(CaptureRequest.CONTROL_AE_LOCK, locked)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, locked)

            // White balance
            if (whiteBalanceKelvin > 0) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToGains(whiteBalanceKelvin))
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_TRANSFORM)
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            applyZoom(b, c)
            b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

            val rect = meteringRect
            val regions = rect?.let { arrayOf(MeteringRectangle(it, MeteringRectangle.METERING_WEIGHT_MAX)) }
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
            if ((c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, regions)

            s.setRepeatingRequest(b.build(), null, handler)
        } catch (_: CameraAccessException) {
            // Camera was closed underneath us; the state callbacks report it.
        } catch (_: IllegalStateException) {
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder, c: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ratioRange: Range<Float>? = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (ratioRange != null) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratioRange.lower + (ratioRange.upper - ratioRange.lower) * zoomPercent / 100f)
                return
            }
        }
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: return
        val zoom = 1f + (maxZoom - 1f) * zoomPercent / 100f
        b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(active, zoom))
    }

    private fun effectiveCrop(c: CameraCharacteristics, active: Rect): Rect {
        val ratioRange: Range<Float>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        if (ratioRange != null) {
            return cropFor(active, ratioRange.lower + (ratioRange.upper - ratioRange.lower) * zoomPercent / 100f)
        }
        val maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        return cropFor(active, 1f + (maxZoom - 1f) * zoomPercent / 100f)
    }

    private fun cropFor(active: Rect, zoom: Float): Rect {
        val z = max(1f, zoom)
        val w = (active.width() / z).toInt()
        val h = (active.height() / z).toInt()
        val l = active.left + (active.width() - w) / 2
        val t = active.top + (active.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    /**
     * Rotation the GL pass must add. The camera service already stores the sensor-orientation rotation in the
     * SurfaceTexture transform matrix (that is why a plain TextureView preview is upright in portrait), so the
     * buffer arrives upright for the device's natural orientation and only the display rotation is left to undo.
     */
    private fun renderRotation(): Int = (360 - displayRotation) % 360

    /** Clockwise rotation of the raw sensor image for the current display rotation (used for tap-to-focus). */
    private fun computeRotation(): Int {
        val cam = info ?: return 0
        return if (cam.facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (cam.sensorOrientation + displayRotation) % 360
        } else {
            (cam.sensorOrientation - displayRotation + 360) % 360
        }
    }

    private companion object {
        val IDENTITY_TRANSFORM = ColorSpaceTransform(
            arrayOf(
                Rational(1, 1), Rational(0, 1), Rational(0, 1),
                Rational(0, 1), Rational(1, 1), Rational(0, 1),
                Rational(0, 1), Rational(0, 1), Rational(1, 1),
            ),
        )

        /** Black-body approximation (Tanner Helland) turned into per-channel gains. */
        fun kelvinToGains(kelvin: Int): RggbChannelVector {
            val t = kelvin / 100.0
            val red = if (t <= 66) 255.0 else (329.698727446 * (t - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
            val green = if (t <= 66) (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0)
            else (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
            val blue = when {
                t >= 66 -> 255.0
                t <= 19 -> 0.0
                else -> (138.5177312231 * ln(t - 10) - 305.0447927307).coerceIn(0.0, 255.0)
            }
            val g = (green / 255.0).toFloat()
            return RggbChannelVector((red / 255.0 * 2).toFloat(), g, g, (blue / 255.0 * 2).toFloat())
        }
    }
}
