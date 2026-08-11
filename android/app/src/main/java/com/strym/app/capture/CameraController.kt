package com.strym.app.capture

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.Executor

private const val TAG = "StrymCamera"
private const val MAX_SESSION_FAILURES = 3
private const val PREVIEW_MAX_WIDTH = 1920

/**
 * Raw Camera2 capture-session manager for the dual-surface path.
 *
 * The camera writes to *both* outputs at once — the UI's viewfinder surface
 * and the H.264 encoder's input surface — so the preview keeps running while
 * the stream is live (no frozen last frame). Idle holds only the preview
 * surface; going live adds the encoder surface and the session is recreated,
 * which re-fires [CameraStreamer]'s "configured" callback so the swap is safe.
 *
 * All state is confined to a dedicated [HandlerThread]; surface swaps are
 * serialized through it and never race the CameraDevice. The encoder surface
 * stays bound until the replacement session is active, so releasing the codec
 * can never destroy a surface the camera still targets.
 */
class CameraController(private val context: Context) {

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val cameraThread = HandlerThread("stry-camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executor { cameraHandler.post(it) }

    private val cameraId: String? by lazy { findRearCameraId() }

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var creating = false
    private var dirty = false
    private var consecutiveFailures = 0

    @Volatile
    private var preview: Surface? = null
    private var previewSize: Size? = null

    @Volatile
    private var encoder: Surface? = null
    private var encoderSize: Size? = null

    private var onError: ((String) -> Unit)? = null
    private var pendingConfigured: () -> Unit = {}

    /**
     * Request the camera feed both [preview] and [encoder] (either may be
     * null). [onConfigured] runs once the new surface set is active — after a
     * live/idle swap it is the point at which the previous encoder surface may
     * be released. Calling with both null releases the camera.
     */
    fun configure(
        preview: Surface?,
        previewSize: Size?,
        encoder: Surface?,
        encoderSize: Size?,
        onError: (String) -> Unit,
        onConfigured: () -> Unit,
    ) {
        cameraHandler.post {
            this.onError = onError
            pendingConfigured = onConfigured
            this.preview = preview
            this.previewSize = previewSize
            this.encoder = encoder
            this.encoderSize = encoderSize
            dirty = true
            applySurfaces()
        }
    }

    /** Tear everything down and stop the camera thread. Idempotent. */
    fun close() {
        cameraHandler.post {
            dirty = false
            pendingConfigured = {}
            creating = false
            session?.close()
            session = null
            camera?.close()
            camera = null
            preview = null
            encoder = null
            onError = null
        }
        cameraThread.quitSafely()
    }

    /** A supported viewfinder size near [aspect], ≤ [maxWidth] wide. */
    fun choosePreviewSize(aspect: Float, maxWidth: Int = PREVIEW_MAX_WIDTH): Size? {
        val map = characteristics()
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        if (sizes.isEmpty()) return null
        val chosen = choosePreviewSize(
            sizes.map { it.width to it.height },
            aspect,
            maxWidth,
        ) ?: return null
        return Size(chosen.first, chosen.second)
    }

    /** Sensor mounting orientation (degrees of clockwise rotation to upright). */
    fun sensorOrientation(): Int = characteristics()
        ?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    // --- session state machine (all on cameraHandler) -----------------------

    private fun applySurfaces() {
        val desired = preview != null || encoder != null
        if (!desired) {
            if (creating) return // settle the in-flight creation, then tear down
            session?.close()
            session = null
            camera?.close()
            camera = null
            dirty = false
            fireConfigured()
            return
        }
        if (camera == null) {
            openCamera()
            return
        }
        if (session != null) {
            if (!dirty) return
            // Surface set changed: close so onClosed recreates with the new set.
            dirty = false
            val old = session
            session = null
            old.close()
            return
        }
        if (!creating) {
            createSession()
        }
        // else: a creation is in flight; onConfigured re-enters with dirty set.
    }

    private fun openCamera() {
        val id = cameraId ?: return notifyError("No back camera found on this device")
        try {
            cameraManager.openCamera(id, cameraCallback, cameraHandler)
        } catch (e: SecurityException) {
            notifyError("The camera permission was revoked")
        } catch (e: CameraAccessException) {
            notifyError("Could not open the camera: ${e.message}")
        }
    }

    private val cameraCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            camera = device
            applySurfaces()
        }

        override fun onDisconnected(device: CameraDevice) {
            camera = null
            runCatching { device.close() }
            notifyError("The camera disconnected")
        }

        override fun onError(device: CameraDevice, error: Int) {
            camera = null
            runCatching { device.close() }
            notifyError("Camera error $error")
        }
    }

    private fun createSession() {
        val device = camera ?: return
        creating = true
        val configs = buildList {
            preview?.let { add(OutputConfiguration(it)) }
            encoder?.let { add(OutputConfiguration(it)) }
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        configs,
                        executor,
                        sessionCallback,
                    ),
                )
            } else {
                device.createCaptureSession(
                    configs.map { it.surface },
                    sessionCallback,
                    cameraHandler,
                )
            }
        } catch (e: CameraAccessException) {
            creating = false
            notifyError("Could not configure the camera: ${e.message}")
            fireConfigured()
        } catch (e: IllegalArgumentException) {
            creating = false
            notifyError("The camera rejected the preview surface: ${e.message}")
            fireConfigured()
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(capturing: CameraCaptureSession) {
            creating = false
            consecutiveFailures = 0
            session = capturing
            startRepeating(capturing)
            applySurfaces()
        }

        override fun onConfigureFailed(capturing: CameraCaptureSession) {
            creating = false
            runCatching { capturing.close() }
            if (++consecutiveFailures >= MAX_SESSION_FAILURES) {
                notifyError("The camera could not be attached to the stream")
                fireConfigured()
            } else {
                applySurfaces()
            }
        }

        override fun onClosed(capturing: CameraCaptureSession) {
            if (session === capturing) session = null
            creating = false
            applySurfaces()
        }
    }

    private fun startRepeating(capturing: CameraCaptureSession) {
        val device = camera ?: return
        val aspect = (previewSize ?: encoderSize)?.let {
            it.width.toFloat() / it.height
        } ?: 0f
        val request = try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                preview?.let { addTarget(it) }
                encoder?.let { addTarget(it) }
                if (aspect > 0f) {
                    set(CaptureRequest.SCALER_CROP_REGION, cropRegion(aspect))
                }
            }
        } catch (e: CameraAccessException) {
            notifyError("Could not start the camera: ${e.message}")
            return
        }
        runCatching { capturing.setRepeatingRequest(request.build(), null, cameraHandler) }
    }

    private fun cropRegion(aspect: Float): android.graphics.Rect {
        val array = characteristics()?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return android.graphics.Rect(0, 0, 1, 1)
        val crop = largestCrop(array.width(), array.height(), aspect)
        return android.graphics.Rect(crop.x, crop.y, crop.x + crop.width, crop.y + crop.height)
    }

    private fun fireConfigured() {
        val callback = pendingConfigured
        pendingConfigured = {}
        callback()
    }

    private fun notifyError(message: String) {
        Log.e(TAG, message)
        onError?.invoke(message)
    }

    private fun characteristics(): CameraCharacteristics? {
        val id = cameraId ?: return null
        return runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
    }

    private fun findRearCameraId(): String? {
        val ids = runCatching { cameraManager.cameraIdList }.getOrNull() ?: return null
        return ids.firstOrNull { id ->
            runCatching {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        } ?: ids.firstOrNull()
    }
}
