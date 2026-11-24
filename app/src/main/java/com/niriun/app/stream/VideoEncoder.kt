package com.niriun.app.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.niriun.app.protocol.StartRequest

/** Surface-input H.264/HEVC encoder whose output buffers are forwarded verbatim as video packets. */
class VideoEncoder(
    private val request: StartRequest,
    private val sender: MediaSender,
    private val backlog: EncoderBacklog,
    private val onFailure: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null

    /** Configures and starts the encoder; returns the Surface to render into. */
    fun start(): Surface {
        val mime = request.mime
        val c = MediaCodec.createEncoderByType(mime)
        val t = HandlerThread("niriun-encoder").also { it.start() }
        val h = Handler(t.looper)
        c.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        if (info.size > 0) {
                            codec.getOutputBuffer(index)?.let { buf ->
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val configBuffer = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (!configBuffer) backlog.onCompleted()
                                sender.sendVideo(if (configBuffer) 0L else info.presentationTimeUs, buf, info.size)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: IllegalStateException) {
                        // Codec was stopped while a callback was in flight.
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    onFailure("Encoder error: ${e.diagnosticInfo}")
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            },
            h,
        )
        try {
            val fps = request.fps.toInt().coerceIn(1, 120)
            val format = MediaFormat.createVideoFormat(mime, request.width, request.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(request.width, request.height, fps, request.mime == "video/hevc"))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (mime == "video/hevc") {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51)
                }
            }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            c.start()
            codec = c
            thread = t
            return surface
        } catch (e: Exception) {
            runCatching { c.release() }
            t.quitSafely()
            throw e
        }
    }

    fun stop() {
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        thread?.quitSafely()
        thread = null
    }

    companion object {
        /** Seconds between IDR frames. The reference uses 15; a shorter GOP recovers faster after any glitch. */
        const val I_FRAME_INTERVAL_SECONDS = 2

        /** Reference bitrate table (bps) for the standard sizes, with its formula for everything else. */
        fun bitrateFor(width: Int, height: Int, fps: Int, hevc: Boolean): Int {
            var rate = when {
                width == 3840 && height == 2160 -> 16_000_000
                width == 2560 && height == 1440 -> 10_000_000
                width == 1920 && height == 1080 -> 6_894_720
                width == 1280 && height == 960 -> 4_300_000
                width == 1280 && height == 720 -> 3_628_800
                width == 960 && height == 540 -> 2_200_000
                width == 640 && height == 480 -> 1_612_800
                width == 640 && height == 360 -> 1_400_000
                else -> (width * 2.7 * height + 1_500_000).toInt()
            }
            if (fps > 40) rate = rate * 3 / 2
            if (hevc) rate = (rate shl 2) / 5
            return minOf(rate, 16_000_000)
        }
    }
}
