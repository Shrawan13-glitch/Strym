package com.strym.app.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "StrymGl"

/** EGL config attribute for surfaces MediaCodec can consume (API 18+). */
private const val EGL_RECORDABLE_ANDROID = 0x3142

/**
 * The GL pipeline between the camera and the H.264 encoder — the on-screen
 * viewfinder is CameraX's job now ([androidx.camera.view.PreviewView]); this
 * class exists solely to upright and fill-crop sensor-native frames into the
 * encoder's input surface, so viewers receive genuinely upright portrait or
 * landscape pixels without rotation metadata.
 *
 * Rotation, crop, and scale all happen here, on the GPU, from one source of
 * truth (`CameraMath.glFillCropTransform`), with the camera's SurfaceTexture
 * matrix applied to the sampling coordinates exactly as the driver provides
 * it — plus a half-turn correction when the ST classifier detects a
 * pre-rotating HAL (`CameraMath.stQuirkCompensationDegrees`).
 *
 * Owns a dedicated thread + EGL context; all GL work is confined to it.
 */
class GlStreamer {

    class Target(
        internal val egl: EGLSurface,
        @Volatile var width: Int,
        @Volatile var height: Int,
        @Volatile var rotationDegrees: Int,
    )

    private val thread = HandlerThread("stry-gl").also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var dummy: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var mvpLocation = 0
    private var stLocation = 0
    private var texLocation = 0
    private var posLocation = 0
    private var uvLocation = 0

    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var bufferSize: Pair<Int, Int>? = null

    @Volatile
    private var encoderTarget: Target? = null

    /** Half-turn correction for pre-rotating HALs; decided from the first frame's ST. */
    @Volatile
    private var stQuirkDegrees = 0

    /**
     * Create the EGL world and the camera-facing [SurfaceTexture] sized to
     * [width]x[height], then hand the surface to [onReady]. Idempotent:
     * CameraX may request surfaces repeatedly across rebinds — the texture is
     * reused and only the buffer size is updated. The previous surface is not
     * released here; CameraX may still hold it until its replacement is
     * provided (the wrapped SurfaceTexture is shared, so nothing leaks but a
     * thin Surface wrapper).
     */
    fun obtainCameraSurface(width: Int, height: Int, onReady: (Surface) -> Unit) {
        handler.post {
            runCatching {
                ensureInit()
                surfaceTexture?.setDefaultBufferSize(width, height)
                bufferSize = width to height
                val st = surfaceTexture ?: error("no SurfaceTexture")
                cameraSurface = Surface(st)
                Log.i(TAG, "camera surface ${width}x$height")
                onReady(cameraSurface!!)
            }.onFailure(::logFatal)
        }
    }

    /** Attach the encoder's input surface (go-live). */
    fun setEncoder(surface: Surface, width: Int, height: Int, rotationDegrees: Int) {
        handler.post {
            runCatching {
                ensureInit()
                encoderTarget?.let(::destroyTarget)
                encoderTarget = makeTarget(surface, width, height, rotationDegrees)
            }.onFailure(::logFatal)
        }
    }

    /**
     * Detach the encoder target; [onDetached] runs on the GL thread after the
     * last draw into it — the point where releasing the codec is safe.
     */
    fun removeEncoder(onDetached: () -> Unit) {
        handler.post {
            runCatching {
                encoderTarget?.let(::destroyTarget)
                encoderTarget = null
            }
            onDetached()
        }
    }

    /** Tear everything down. Idempotent. */
    fun close() {
        handler.post {
            encoderTarget?.let(::destroyTarget)
            encoderTarget = null
            cameraSurface?.release()
            cameraSurface = null
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
            GLES20.glDeleteProgram(program)
            program = 0
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, dummy)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
        }
        thread.quitSafely()
    }

    // --- GL thread ----------------------------------------------------------

    private fun logFatal(t: Throwable) {
        Log.e(TAG, "GL pipeline failure", t)
    }

    /** Lazily bring up the EGL world; safe to call from any GL-thread task. */
    private fun ensureInit() {
        if (program == 0) {
            initGl()
            Log.i(TAG, "GL initialized")
        }
    }

    private fun initGl() {
        if (program != 0) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, version, 0, version, 1)) {
            "EGL display unavailable"
        }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val counts = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, counts, 0) && counts[0] > 0) {
            "No recordable EGL config"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
        dummy = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(dummy != EGL14.EGL_NO_SURFACE) { "Pbuffer failed" }
        EGL14.eglMakeCurrent(display, dummy, dummy, context)

        texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        surfaceTexture = SurfaceTexture(texture, false).apply {
            // No-Handler listener fires on a binder thread; every frame is
            // posted onto the GL thread, which owns updateTexImage.
            setOnFrameAvailableListener { handler.post { drawFrame() } }
        }
        buildProgram()
    }

    private fun buildProgram() {
        // The RootEncoder/Grafika scheme: geometry (rotation + fill-crop) lives
        // only in uMVP on the position side; the camera's SurfaceTexture matrix
        // is applied to the quad UVs exactly as the driver provides it.
        val vertex = """
            uniform mat4 uMVP;
            uniform mat4 uST;
            attribute vec4 aPos;
            attribute vec4 aUV;
            varying vec2 vUV;
            void main() {
                gl_Position = uMVP * aPos;
                vUV = (uST * aUV).xy;
            }
        """.trimIndent()
        val fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUV;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vUV);
            }
        """.trimIndent()
        program = GLES20.glCreateProgram().also { created ->
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            GLES20.glAttachShader(created, vs)
            GLES20.glAttachShader(created, fs)
            GLES20.glLinkProgram(created)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE) {
                "link failed: ${GLES20.glGetProgramInfoLog(created)}"
            }
            mvpLocation = GLES20.glGetUniformLocation(created, "uMVP")
            stLocation = GLES20.glGetUniformLocation(created, "uST")
            texLocation = GLES20.glGetUniformLocation(created, "uTex")
            posLocation = GLES20.glGetAttribLocation(created, "aPos")
            uvLocation = GLES20.glGetAttribLocation(created, "aUV")
            Log.i(TAG, "program linked: pos=$posLocation uv=$uvLocation mvp=$mvpLocation")
        }
    }

    private fun compile(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }

    private val quad: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(16 * FLOAT_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply {
                // x, y, u, v triangle strip covering the full target; v rises
                // toward screen-top, matching the ST matrix's expected input.
                put(floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ))
                position(0)
            }
    }

    private val stMatrix = FloatArray(16)

    private var frameCount = 0
    private var frameLog = 0

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        val size = bufferSize ?: return
        if (encoderTarget == null) {
            if (frameLog++ % 60 == 0) Log.w(TAG, "frame dropped: no encoder target attached")
            return
        }
        frameCount++
        try {
            st.updateTexImage()
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }
        if (frameCount % 60 == 1) Log.i(TAG, "frame #$frameCount enc=${encoderTarget != null}")
        st.getTransformMatrix(stMatrix)
        if (frameCount == 1) {
            stQuirkDegrees = stQuirkCompensationDegrees(stMatrix)
            Log.i(
                TAG,
                "camera ST family: ${if (stQuirkDegrees == 0) "standard" else "transposing (+$stQuirkDegrees°)"}",
            )
        }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(texLocation, 0)
        // The camera's ST maps buffer-image coords to real texels; applied
        // as-is, per RootEncoder/Grafika. Geometry stays in uMVP only.
        GLES20.glUniformMatrix4fv(stLocation, 1, false, stMatrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 4 * FLOAT_BYTES, quad)
        GLES20.glEnableVertexAttribArray(posLocation)
        if (uvLocation >= 0) {
            quad.position(2)
            GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 4 * FLOAT_BYTES, quad)
            GLES20.glEnableVertexAttribArray(uvLocation)
        }
        encoderTarget?.let(::drawInto)
        GLES20.glDisableVertexAttribArray(posLocation)
        GLES20.glDisableVertexAttribArray(uvLocation)
    }

    private fun drawInto(target: Target) {
        if (!EGL14.eglMakeCurrent(display, target.egl, target.egl, context)) {
            Log.w(TAG, "makeCurrent failed for target ${target.width}x${target.height}: ${EGL14.eglGetError()}")
            return
        }
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val size = bufferSize ?: return
        val rotation = target.rotationDegrees + stQuirkDegrees
        GLES20.glUniformMatrix4fv(
            mvpLocation, 1, false,
            glFillCropTransform(
                rotation, size.first, size.second, target.width, target.height,
            ),
            0,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        surfaceTexture?.let { EGLExt.eglPresentationTimeANDROID(display, target.egl, it.timestamp) }
        if (!EGL14.eglSwapBuffers(display, target.egl)) {
            Log.w(TAG, "swapBuffers failed: ${EGL14.eglGetError()}")
        } else if (frameCount % 60 == 1) {
            Log.i(TAG, "swapped ${size.first}x${size.second} → ${target.width}x${target.height} rot=$rotation")
        }
    }

    private fun makeTarget(surface: Surface, width: Int, height: Int, rotationDegrees: Int): Target {
        val cfg = config ?: error("EGL not initialised")
        val egl = EGL14.eglCreateWindowSurface(display, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(egl != EGL14.EGL_NO_SURFACE) { "EGL window surface failed" }
        return Target(egl, width, height, rotationDegrees)
    }

    private fun destroyTarget(target: Target) {
        EGL14.eglMakeCurrent(
            display, dummy, dummy, context,
        )
        EGL14.eglDestroySurface(display, target.egl)
    }

    companion object {
        private const val FLOAT_BYTES = 4
    }
}
