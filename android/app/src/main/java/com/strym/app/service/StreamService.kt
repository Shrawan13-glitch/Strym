package com.strym.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.strym.app.R
import com.strym.app.StrymApp
import com.strym.app.capture.AudioRecorder
import com.strym.app.capture.CameraStreamer
import com.strym.app.capture.SessionClock
import com.strym.app.session.RealSessionFactory
import com.strym.app.session.StreamController
import com.strym.app.session.StreamPhase
import com.strym.app.settings.BroadcastSettings
import com.strym.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the broadcast alive with the screen off. Owns the [StreamController]
 * (one session per service lifetime), the [CameraStreamer] (camera → encoder →
 * session) and the [AudioRecorder] (mic → AAC encoder → session); the UI binds
 * to read `uiState`, hand over its viewfinder, and trigger go-live / stop.
 *
 * A [LifecycleService] so CameraX binds its use cases to this service's
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

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    override fun onCreate() {
        super.onCreate()
        controller = StreamController(
            RealSessionFactory,
            resolveError = { error -> getString(error.stringRes, error.detail) },
        )
        camera = CameraStreamer(this, this)
        audio = AudioRecorder()
        scope.launch {
            var previous: StreamPhase? = null
            controller.uiState.collect { state ->
                if (state.hasSession) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(state.phase))
                }
                // Keyframe discipline on every recovery edge: a fresh IDR is
                // what turns "reconnected" into viewers actually seeing video.
                when (state.phase) {
                    StreamPhase.RECONNECTING -> camera.requestKeyframe()
                    StreamPhase.LIVE ->
                        if (previous == StreamPhase.RECONNECTING) camera.requestKeyframe()
                    else -> {}
                }
                previous = state.phase
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Resume path after the process was swiped away mid-broadcast:
        // Android 14+ forbids restarting a camera FGS from the background,
        // so the user taps the notification (a foreground start) and we pick
        // up from persisted settings.
        if (intent?.getBooleanExtra(EXTRA_RESUME, false) == true &&
            !controller.uiState.value.hasSession
        ) {
            scope.launch {
                val settings = SettingsRepository(applicationContext).current()
                goLive(settings)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * The task was swiped out of recents while live. The process (and with it
     * the session) dies moments later; post a persistent, high-visibility
     * notification so one tap restores the broadcast. The notification
     * outlives the process — it is system-managed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = controller.uiState.value
        if (state.hasSession && state.phase in LIVE_PHASES) {
            val resume = PendingIntent.getService(
                this,
                0,
                Intent(this, StreamService::class.java).putExtra(EXTRA_RESUME, true),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            getSystemService(NotificationManager::class.java).notify(
                RESUME_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_STREAMING)
                    .setSmallIcon(R.drawable.ic_stream)
                    .setContentTitle(getString(R.string.notification_resume_title))
                    .setContentText(getString(R.string.notification_resume_text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setOngoing(false)
                    .addAction(0, getString(R.string.notification_resume_action), resume)
                    .setContentIntent(resume)
                    .build(),
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    /** Show the UI viewfinder; CameraX owns every display transform. */
    fun attachPreview(provider: Preview.SurfaceProvider) {
        camera.attachPreview(provider)
    }

    /** Stop showing the viewfinder; a live broadcast keeps its GL feed. */
    fun detachPreview() {
        camera.detachPreview()
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
            // One question decides the whole broadcast shape — the hold at
            // go-live (the UI locks orientation while live, so it cannot
            // change under us).
            val portrait =
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            if (!controller.goLive(settings, portrait)) {
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
            val clock = SessionClock()
            camera.startEncoding(settings, portrait, controller, ::captureFailed, clock)
            if (settings.audioEnabled) {
                audio.start(controller, ::captureFailed, clock)
            }
            acquireWakeLock()
            acquireWifiLock()
        }
    }

    /** Hold a partial wake lock while live so Doze cannot starve capture. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strym:stream").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    /**
     * Keep the Wi-Fi radio out of power-save while live. `LOW_LATENCY` is the
     * only non-deprecated mode and is documented as screen-on/foreground —
     * exactly the states (control center, floating window, app switcher)
     * where streams used to stall. With the screen fully off the radio may
     * still sleep; the core's liveness watchdog then redials us.
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "strym:wifi").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
    }

    /** Stop the session, leave the foreground, and release the start. */
    fun endBroadcast() {
        scope.launch { teardown() }
    }

    override fun onDestroy() {
        camera.close()
        audio.stop()
        releaseWakeLock()
        releaseWifiLock()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun teardown() {
        camera.stopEncoding()
        audio.stop()
        controller.stopSession()
        releaseWakeLock()
        releaseWifiLock()
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
        const val RESUME_NOTIFICATION_ID = 2
        const val EXTRA_RESUME = "resume"

        /** Phases in which the broadcast is (or should soon be) on the wire. */
        private val LIVE_PHASES = setOf(StreamPhase.CONNECTING, StreamPhase.LIVE, StreamPhase.RECONNECTING)
    }
}
