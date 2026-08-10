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
import com.strym.app.R
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
 * Keeps the stream session alive with the screen off. Owns the
 * [StreamController] (one session per service lifetime); the UI binds to
 * read its `uiState` and to trigger go-live / stop.
 *
 * Started as a `camera|microphone` foreground service when a broadcast
 * begins — the runtime enforces that the matching permissions are granted,
 * which the UI's permission gate guarantees before [goLive] is reachable.
 */
class StreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()

    lateinit var controller: StreamController
        private set

    inner class LocalBinder : Binder() {
        fun service(): StreamService = this@StreamService
    }

    override fun onCreate() {
        super.onCreate()
        controller = StreamController(RealSessionFactory)
        scope.launch {
            controller.uiState.collect { state ->
                if (state.hasSession) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(state.phase))
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** Create + start the session, then promote this service to the foreground. */
    fun goLive(settings: BroadcastSettings) {
        scope.launch {
            if (!controller.goLive(settings)) return@launch
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
        }
    }

    /** Stop the session, leave the foreground, and tear the service down. */
    fun endBroadcast() {
        scope.launch {
            controller.stopSession()
            ServiceCompat.stopForeground(this@StreamService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
