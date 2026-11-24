package com.niriun.app.stream

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Size
import com.niriun.app.protocol.VideoMode
import kotlin.math.abs

data class CameraInfo(
    val id: String,
    val name: String,
    val facing: Int,
    val sensorOrientation: Int,
    val sizes: List<Size>,
    val maxFps: Int,
    val hasFlash: Boolean,
)

/** Camera2 inventory plus the (size, fps) modes offered to the PC. */
class CameraCatalog(context: Context) {
    val cameras: List<CameraInfo> = enumerate(context.getSystemService(Context.CAMERA_SERVICE) as CameraManager)

    fun byId(id: String?): CameraInfo? = cameras.firstOrNull { it.id == id }

    /** First entry is what the PC selects by default (640x480@30), as in the reference. */
    fun modes(camera: CameraInfo): List<VideoMode> {
        val encoder = encoderCapabilities("video/avc")
        val offered = ArrayList<VideoMode>()
        for ((w, h) in PRESET_SIZES) {
            if (camera.sizes.none { it.width == w && it.height == h }) continue
            for (fps in PRESET_FPS) {
                if (fps > 30 && camera.maxFps < fps) continue
                if (encoder != null && !encoder.areSizeAndRateSupported(w, h, fps.toDouble())) continue
                offered += VideoMode(w, h, fps)
            }
        }
        if (offered.isEmpty()) {
            // No preset matches this camera: offer its best landscape size up to 1080p so the PC always has a choice.
            val best = camera.sizes.filter { it.width >= it.height && it.width <= 1920 }.maxByOrNull { it.width * it.height }
                ?: camera.sizes.firstOrNull()
            if (best != null) offered += VideoMode(best.width, best.height, 30)
        }
        val default = offered.indexOfFirst { it.width == 640 && it.height == 480 && it.fps == 30 }
        if (default > 0) offered.add(0, offered.removeAt(default))
        return offered
    }

    /** Camera output size closest to the requested stream size (same aspect first, then nearest area). */
    fun bestSize(camera: CameraInfo, width: Int, height: Int): Size {
        val wantAspect = width.toFloat() / height
        return camera.sizes.minByOrNull { s ->
            val aspectPenalty = if (abs(s.width.toFloat() / s.height - wantAspect) <= 0.02f) 0L else 1L shl 40
            val areaDiff = abs(s.width.toLong() * s.height - width.toLong() * height)
            val undersize = if (s.width < width || s.height < height) 1L shl 30 else 0L
            aspectPenalty + undersize + areaDiff
        } ?: Size(width, height)
    }

    /** Preview-only size: the largest 16:9 (or nearest) size not above 1280x720. */
    fun previewSize(camera: CameraInfo): Size {
        val candidates = camera.sizes.filter { it.width <= 1280 && it.height <= 720 }
        return candidates.maxByOrNull { it.width * it.height } ?: camera.sizes.minByOrNull { it.width * it.height } ?: Size(640, 480)
    }

    companion object {
        private val PRESET_SIZES = listOf(
            3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 960, 1280 to 720, 960 to 540, 640 to 480, 640 to 360,
        )
        private val PRESET_FPS = listOf(30, 60)

        fun encoderCapabilities(mime: String): MediaCodecInfo.VideoCapabilities? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .mapNotNull { it.getCapabilitiesForType(mime).videoCapabilities }
                .firstOrNull()

        private fun enumerate(manager: CameraManager): List<CameraInfo> {
            val result = ArrayList<CameraInfo>()
            for (id in manager.cameraIdList) {
                val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                // Skip depth/IR/logical-only helpers that cannot produce normal color frames.
                if (caps != null && CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps.toList()) continue
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: continue
                val facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_EXTERNAL
                val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                result += CameraInfo(
                    id = id,
                    name = "",
                    facing = facing,
                    sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    sizes = sizes,
                    maxFps = fpsRanges?.maxOfOrNull { it.upper } ?: 30,
                    hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                )
            }
            return nameThem(result)
        }

        private fun nameThem(cameras: List<CameraInfo>): List<CameraInfo> {
            fun label(f: Int) = when (f) {
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                else -> "External"
            }
            return cameras.map { cam ->
                val same = cameras.count { it.facing == cam.facing }
                cam.copy(name = if (same > 1) "${label(cam.facing)} camera ${cam.id}" else "${label(cam.facing)} camera")
            }
        }
    }
}
