package com.strym.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.strym.app.R
import com.strym.app.StrymApp
import com.strym.app.capture.AudioRecorder
import com.strym.app.capture.CameraStreamer
import com.strym.app.session.RealSessionFactory
import com.strym.app.session.StreamController
import com.strym.app.session.StreamPhase
import com.strym.app.settings.BroadcastSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the broadcast alive with the screen off. Owns the [StreamController]
 * (one session per service lifetime), the [CameraStreamer] (camera → encoder →
 * session) and the [AudioRecorder] (mic → AAC encoder → session); the UI binds
 * to read `uiState`, hand over its viewfinder surface, and trigger go-live /
 * stop.
 *
 * A [LifecycleService] so CameraX can bind its use cases to this service's
 * lifecycle instead of the activity's — capture then keeps running when the
 * screen turns off (the foreground type enforces the camera permission, which
 * the UI's permission gate guarantees before [goLive] is reachable).
 */
class StreamService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()

    lateinit var controller: StreamController
        private set

    lateinit var camera: CameraStreamer
        private set

    lateinit var audio: AudioRecorder
        private set

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    override fun onCreate() {
        super.onCreate()
        controller = StreamController(
            RealSessionFactory,
            resolveError = { error -> getString(error.stringRes, error.detail) },
        )
        camera = CameraStreamer(this)
        audio = AudioRecorder()
        scope.launch {
            controller.uiState.collect { state ->
                if (state.hasSession) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(state.phase))
                }
                if (state.phase == StreamPhase.RECONNECTING) {
                    // Keyframe discipline: cut to a fresh IDR so the viewer
                    // resyncs promptly once the connection returns.
                    camera.requestKeyframe()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    /** Create + start the session and capture, then promote to the foreground. */
    fun goLive(settings: BroadcastSettings) {
        // Mask the stream key in every core log record from this session on —
        // the log buffer is shared and its dump may leave the app.
        (applicationContext as StrymApp).logRedactor.addSecret(settings.streamKey)
        // Enter the STARTED state: CameraX only runs the camera for lifecycles
        // at least STARTED, and the foreground promotion below builds on it.
        startService(Intent(this, StreamService::class.java))
        scope.launch {
            if (!controller.goLive(settings)) {
                stopSelf()
                return@launch
            }
            val types = if (settings.audioEnabled) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            ServiceCompat.startForeground(
                this@StreamService,
                NOTIFICATION_ID,
                buildNotification(StreamPhase.CONNECTING),
                types,
            )
            camera.startEncoding(settings, controller, ::captureFailed)
            if (settings.audioEnabled) {
                audio.start(controller, ::captureFailed)
            }
        }
    }

    /** Stop the session, leave the foreground, and release the start. */
    fun endBroadcast() {
        scope.launch { teardown() }
    }

    override fun onDestroy() {
        camera.close()
        audio.stop()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun teardown() {
        camera.stopEncoding()
        audio.stop()
        controller.stopSession()
        ServiceCompat.stopForeground(this@StreamService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Fatal capture failure: stop everything and show why. */
    private fun captureFailed(message: String) {
        scope.launch {
            teardown()
            controller.reportCaptureError(message)
        }
    }

    private fun buildNotification(phase: StreamPhase): Notification =
        NotificationCompat.Builder(this, CHANNEL_STREAMING)
            .setSmallIcon(R.drawable.ic_stream)
            .setContentTitle(getString(R.string.notification_streaming_title))
            .setContentText(getString(phaseText(phase)))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()

    private fun phaseText(phase: StreamPhase): Int = when (phase) {
        StreamPhase.IDLE -> R.string.state_idle
        StreamPhase.CONNECTING -> R.string.state_connecting
        StreamPhase.LIVE -> R.string.state_live
        StreamPhase.RECONNECTING -> R.string.state_reconnecting
        StreamPhase.EXHAUSTED -> R.string.state_exhausted
        StreamPhase.STOPPED -> R.string.state_stopped
    }

    companion object {
        const val CHANNEL_STREAMING = "streaming"
        const val NOTIFICATION_ID = 1
    }
}
