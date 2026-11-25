package com.niriun.app.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Camera frames -> OpenGL ES 2 -> one or two window surfaces (local preview and the video encoder).
 *
 * The camera writes into [start]'s returned Surface (an external OES texture). Each camera frame is drawn
 * rotated (and optionally mirrored) with letterboxing into every attached output. Encoder output carries
 * `eglPresentationTimeANDROID` stamps from [StreamClock] so the wire timestamps match the ACK clock.
 *
 * All GL/EGL work happens on the renderer's own thread; the public setters are thread-safe.
 */
class GlRenderer(
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    /** Size of the picture after the SurfaceTexture transform (swapped when the sensor is rotated 90/270). */
    private val contentWidth: Int,
    private val contentHeight: Int,
    private val clock: StreamClock,
    /** Called on the GL thread before each camera frame; true means "skip the encoder for this frame". */
    private val dropEncoderFrame: () -> Boolean,
    /** Called after a frame was handed to the encoder surface. */
    private val onEncoderFrame: () -> Unit,
) {
    private class Output(val surface: Surface, val egl: EGLSurface, val isEncoder: Boolean)

    private val thread = HandlerThread("niriun-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMvp = 0
    private var uTexMatrix = 0
    private var uBrightness = 0
    private var uContrast = 0
    private var uSaturation = 0
    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    private var preview: Output? = null
    private var encoder: Output? = null

    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private val sizeProbe = IntArray(1)

    @Volatile private var rotation = 0
    @Volatile private var mirror = false
    @Volatile private var released = false
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().also { it.put(QUAD).position(0) }

    /** Initializes GL on the renderer thread and returns the Surface the camera must write to. */
    fun start(): Surface {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                initGl()
            } catch (t: Throwable) {
                failure = t
            }
            ready.countDown()
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "GL init timed out" }
        failure?.let { throw IllegalStateException("GL init failed: ${it.message}", it) }
        return checkNotNull(cameraSurface)
    }

    fun setRotation(degrees: Int) {
        rotation = ((degrees % 360) + 360) % 360
    }

    fun setMirror(enabled: Boolean) {
        mirror = enabled
    }

    /** Colour adjustment applied to the preview and the encoder alike: brightness -1..1, contrast 0.5..1.5, saturation 0..2. */
    fun setLook(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness
        this.contrast = contrast
        this.saturation = saturation
    }

    fun setPreviewSurface(surface: Surface?) = runOnGl { replaceOutput(isEncoder = false, surface) }

    /** Blocks until the EGL surface is attached/destroyed, so the caller may release the Surface afterwards. */
    fun setEncoderSurface(surface: Surface?) = runOnGl { replaceOutput(isEncoder = true, surface) }

    private fun runOnGl(block: () -> Unit) {
        if (released) return
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        handler.post {
            try {
                teardownGl()
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    // ---- GL thread ----

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        config = configs[0]

        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        // A current surface is needed before the OES texture exists; fall back to surfaceless if pbuffers are refused.
        idleSurface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            ?: EGL14.EGL_NO_SURFACE
        makeIdleCurrent()

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTexture)
        st.setDefaultBufferSize(bufferWidth, bufferHeight)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        surfaceTexture = st
        cameraSurface = Surface(st)
    }

    private fun makeIdleCurrent() {
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
    }

    private fun replaceOutput(isEncoder: Boolean, surface: Surface?) {
        if (released || display == EGL14.EGL_NO_DISPLAY) return
        val old = if (isEncoder) encoder else preview
        if (old != null) {
            makeIdleCurrent()
            EGL14.eglDestroySurface(display, old.egl)
        }
        val created = if (surface != null && surface.isValid) {
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (egl == null || egl == EGL14.EGL_NO_SURFACE) null else Output(surface, egl, isEncoder)
        } else {
            null
        }
        if (isEncoder) encoder = created else preview = created
    }

    private fun onFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        val enc = encoder
        // Back-pressure: the PC has not ACKed (waits up to 100 ms) or the encoder is backed up -> skip the encoder.
        val feedEncoder = enc != null && !dropEncoderFrame()
        try {
            makeIdleCurrent()
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (_: RuntimeException) {
            return
        }
        // Encoder first: it is the latency-critical consumer; the preview swap may wait for vsync.
        if (feedEncoder) {
            enc?.let {
                draw(it)
                onEncoderFrame()
            }
        }
        preview?.let { draw(it) }
        makeIdleCurrent()
    }

    private fun draw(out: Output) {
        if (!EGL14.eglMakeCurrent(display, out.egl, out.egl, context)) return
        EGL14.eglSwapInterval(display, 0)
        EGL14.eglQuerySurface(display, out.egl, EGL14.EGL_WIDTH, sizeProbe, 0)
        val w = sizeProbe[0]
        EGL14.eglQuerySurface(display, out.egl, EGL14.EGL_HEIGHT, sizeProbe, 0)
        val h = sizeProbe[0]
        if (w <= 0 || h <= 0) return

        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        computeMvp(w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uBrightness, brightness)
        GLES20.glUniform1f(uContrast, contrast)
        GLES20.glUniform1f(uSaturation, saturation)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        if (out.isEncoder) {
            EGLExt.eglPresentationTimeANDROID(display, out.egl, (clock.seconds().toDouble() * 1_000_000_000.0).toLong())
        }
        EGL14.eglSwapBuffers(display, out.egl)
    }

    /** `P · mirror · fit · rotate(-R) · bufferAspect`: rotate CW by R, letterbox into the output. */
    private fun computeMvp(outW: Int, outH: Int) {
        val rot = rotation
        val outAspect = outW.toFloat() / outH
        val bufAspect = contentWidth.toFloat() / contentHeight
        val swapped = rot % 180 != 0
        val extentW = if (swapped) 2f else 2f * bufAspect
        val extentH = if (swapped) 2f * bufAspect else 2f
        val fit = min(2f * outAspect / extentW, 2f / extentH)
        Matrix.orthoM(mvp, 0, -outAspect, outAspect, -1f, 1f, -1f, 1f)
        if (mirror) Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
        Matrix.scaleM(mvp, 0, fit, fit, 1f)
        Matrix.rotateM(mvp, 0, -rot.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(mvp, 0, bufAspect, 1f, 1f)
    }

    private fun teardownGl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        makeIdleCurrent()
        preview?.let { EGL14.eglDestroySurface(display, it.egl) }
        encoder?.let { EGL14.eglDestroySurface(display, it.egl) }
        preview = null
        encoder = null
        cameraSurface?.release()
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        cameraSurface = null
        surfaceTexture = null
        if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
        if (program != 0) GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (idleSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, idleSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        // x, y, u, v
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord);
                float luma = dot(c.rgb, vec3(0.213, 0.715, 0.072));
                vec3 rgb = mix(vec3(luma), c.rgb, uSaturation);
                rgb = (rgb - 0.5) * uContrast + 0.5 + uBrightness;
                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);
            }
        """
    }
}
