package com.strym.app.ui.live

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strym.app.R
import com.strym.app.service.StreamService
import com.strym.app.session.StatsSnapshot
import com.strym.app.session.StreamPhase
import com.strym.app.session.UiState
import com.strym.app.settings.BroadcastSettings
import com.strym.app.settings.BatteryPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(
    service: StreamService?,
    settings: BroadcastSettings,
    onOpenSettings: () -> Unit,
) {
    val controller = service?.controller
    val fallback = remember { MutableStateFlow(UiState()) }
    val state by (controller?.uiState ?: fallback).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Notification permission is optional: ask opportunistically on go-live,
    // never block the stream on it.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — the stream works either way */ }

    // Same spirit for the battery-optimization exemption: explained once,
    // in plain language, right before the first broadcast. Either answer
    // proceeds to go live; the settings row remains for anyone who skips.
    var showBatteryDialog by remember { mutableStateOf(false) }

    fun goLiveNow() {
        // Freeze the hold for the whole broadcast: the encoded shape and its
        // uprighting rotation are decided from the orientation at go-live,
        // so rotating mid-stream must not change them (the StreamCaster
        // model — one question, asked once).
        findActivity(context)?.requestedOrientation = currentOrientationLock(context)
        service?.goLive(settings)
    }

    // Release the lock whenever no session exists (stopped, failed, or never
    // started) so normal rotation resumes.
    LaunchedEffect(state.hasSession) {
        if (!state.hasSession) {
            findActivity(context)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(Modifier.fillMaxSize()) {
        CameraPreview(service, Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateBadge(state.phase)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.live_settings),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.stats?.let { stats ->
                StatsRow(stats, Modifier.fillMaxWidth())
            }
            state.errorMessage?.let { message ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Controls(
                state = state,
                settings = settings,
                service = service,
                onGoLive = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (BatteryPrompt.isExempt(context) || BatteryPrompt.wasAsked(context)) {
                        goLiveNow()
                    } else {
                        // First broadcast: explain the exemption once, then go
                        // live either way.
                        showBatteryDialog = true
                    }
                },
                onStop = { service?.endBroadcast() },
                onRetry = { scope.launch { controller?.retry() } },
            )
        }
    }

    if (showBatteryDialog) {
        BatteryExemptionDialog(
            onAllow = {
                showBatteryDialog = false
                BatteryPrompt.markAsked(context)
                context.startActivity(BatteryPrompt.requestIntent(context))
                goLiveNow()
            },
            onNotNow = {
                showBatteryDialog = false
                BatteryPrompt.markAsked(context)
                goLiveNow()
            },
            onDismissed = { showBatteryDialog = false },
        )
    }
}

/**
 * Plain-language pre-prompt before the system's battery allowlist dialog —
 * what Play review expects for this permission, and what makes the choice an
 * informed one. Both answers proceed to go live; backing out of the dialog
 * skips the broadcast and leaves the prompt un-asked so we try again next time.
 */
@Composable
private fun BatteryExemptionDialog(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    onDismissed: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissed,
        title = { Text(stringResource(R.string.battery_dialog_title)) },
        text = { Text(stringResource(R.string.battery_dialog_body)) },
        confirmButton = {
            Button(onClick = onAllow) { Text(stringResource(R.string.battery_dialog_allow)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onNotNow) {
                Text(stringResource(R.string.battery_dialog_not_now))
            }
        },
    )
}

@Composable
private fun Controls(
    state: UiState,
    settings: BroadcastSettings,
    service: StreamService?,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.phase == StreamPhase.EXHAUSTED -> Row {
            Button(onClick = onRetry) { Text(stringResource(R.string.live_try_again)) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.live_give_up)) }
        }

        state.failedConnect -> Row {
            Button(onClick = onRetry) { Text(stringResource(R.string.live_retry)) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.live_give_up)) }
        }

        state.hasSession -> Button(
            onClick = onStop,
            enabled = service != null,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.live_stop))
        }

        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!settings.canStart) {
                Text(
                    text = stringResource(R.string.live_configure_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onGoLive,
                enabled = service != null && settings.canStart,
            ) {
                Text(stringResource(R.string.live_go_live))
            }
        }
    }
}

@Composable
fun StateBadge(phase: StreamPhase) {
    val (label, color) = when (phase) {
        StreamPhase.IDLE -> R.string.state_idle to Color(0xFF616161)
        StreamPhase.CONNECTING -> R.string.state_connecting to Color(0xFFFFB300)
        StreamPhase.LIVE -> R.string.state_live to Color(0xFFE53935)
        StreamPhase.RECONNECTING -> R.string.state_reconnecting to Color(0xFFFFB300)
        StreamPhase.EXHAUSTED -> R.string.state_exhausted to Color(0xFFE53935)
        StreamPhase.STOPPED -> R.string.state_stopped to Color(0xFF616161)
    }
    Surface(shape = RoundedCornerShape(50), color = color) {
        Text(
            text = stringResource(label),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

@Composable
fun StatsRow(stats: StatsSnapshot, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StatCell(stringResource(R.string.stats_bitrate), formatBitrate(stats.bitrateOutBps))
            StatCell(stringResource(R.string.stats_drop), formatDropRatio(stats.dropRatio))
            StatCell(stringResource(R.string.stats_lag), formatLagMs(stats.bufferLagMs))
            StatCell(stringResource(R.string.stats_rtt), formatRtt(stats.rttMs))
            StatCell(stringResource(R.string.stats_uptime), formatUptime(stats.uptimeMs))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

/**
 * Live viewfinder over the GL pipeline, the stock-camera way: one full-screen
 * surface. Rotation, fill-crop, and uprighting happen inside [GlStreamer] —
 * the same pass that feeds the encoder, so what you see is exactly what
 * viewers get, in any hold. The UI's only jobs are handing its surface over
 * and reporting the current display rotation.
 */
@Composable
fun CameraPreview(service: StreamService?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val surfaceView = remember(context) { SurfaceView(context) }

    DisposableEffect(service) {
        var attached = false

        // Rotation is recomputed from the *live* display rotation on every
        // push: reading it at composition time races service binding (the
        // old "sideways preview" bug) and misses mid-hold changes.
        fun push(width: Int, height: Int) {
            val camera = service?.camera ?: return
            if (width == 0 || height == 0 || !surfaceView.holder.surface.isValid) return
            val sensor = camera.sensorOrientation()
            val deviceRotation = (context.display?.rotation ?: 0) * 90
            val upright = ((sensor - deviceRotation) % 360 + 360) % 360
            if (!attached) {
                attached = true
                camera.setPreviewSurface(surfaceView.holder.surface, width, height, upright)
            } else {
                camera.updatePreviewTransform(width, height, upright)
            }
        }

        val callbacks = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                push(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                attached = false
                service?.camera?.setPreviewSurface(null, 0, 0, 0)
            }
        }
        surfaceView.holder.addCallback(callbacks)
        if (surfaceView.holder.surface != null && surfaceView.width > 0) {
            push(surfaceView.width, surfaceView.height)
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            push(surfaceView.width, surfaceView.height)
        }
        surfaceView.addOnLayoutChangeListener(layoutListener)
        onDispose {
            surfaceView.removeOnLayoutChangeListener(layoutListener)
            surfaceView.holder.removeCallback(callbacks)
            service?.camera?.setPreviewSurface(null, 0, 0, 0)
        }
    }

    AndroidView(
        factory = { surfaceView },
        modifier = modifier.background(Color.Black),
    )
}
/** The activity hosting this context, for orientation locks. */
private fun findActivity(context: Context): Activity? {
    var current: Context = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Lock to whichever orientation the device is held in right now. */
private fun currentOrientationLock(context: Context): Int =
    if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }
