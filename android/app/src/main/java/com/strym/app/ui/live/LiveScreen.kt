package com.strym.app.ui.live

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.strym.app.capture.computePreviewTransform
import com.strym.app.service.StreamService
import com.strym.app.session.StatsSnapshot
import com.strym.app.session.StreamPhase
import com.strym.app.session.UiState
import com.strym.app.settings.BroadcastSettings
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
                    service?.goLive(settings)
                },
                onStop = { service?.endBroadcast() },
                onRetry = { scope.launch { controller?.retry() } },
            )
        }
    }
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
 * Live viewfinder over raw Camera2. The [TextureView]'s surface is handed to
 * the service's camera streamer, which runs the Camera2 session and keeps the
 * viewfinder *and* the encoder's input surface bound at once — so the preview
 * keeps showing live video while streaming instead of freezing on the last
 * frame. The buffer is sensor-native (landscape); a rotation + fill transform
 * makes it upright on screen. Passing the surface (and holding the service's
 * camera open) only happens while the UI is visible or a stream is live, so
 * capture survives the screen turning off.
 */
@Composable
fun CameraPreview(service: StreamService?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sensorOrientation = remember { service?.camera?.sensorOrientation() ?: 0 }
    val textureView = remember(context) { TextureView(context) }
    val display = remember(context) { context.display }
    var chosenSize by remember { mutableStateOf<Size?>(null) }

    fun updateTransform() {
        val bufferWidth = chosenSize?.width ?: return
        val bufferHeight = chosenSize?.height ?: return
        val transform = computePreviewTransform(
            sensorOrientation = sensorOrientation,
            deviceRotationDegrees = (display?.rotation ?: 0) * 90,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            viewWidth = textureView.width,
            viewHeight = textureView.height,
        )
        val matrix = Matrix()
        val cx = textureView.width / 2f
        val cy = textureView.height / 2f
        matrix.postScale(transform.scale, transform.scale, cx, cy)
        matrix.postRotate(transform.rotationDegrees.toFloat(), cx, cy)
        textureView.setTransform(matrix)
    }

    DisposableEffect(service) {
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val size = service?.camera?.choosePreviewSize()
                if (size != null) surface.setDefaultBufferSize(size.width, size.height)
                chosenSize = size
                service?.camera?.setPreviewSurface(Surface(surface), size)
                updateTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                val size = service?.camera?.choosePreviewSize()
                if (size != null) surface.setDefaultBufferSize(size.width, size.height)
                chosenSize = size
                updateTransform()
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
        textureView.setSurfaceTextureListener(listener)
        if (textureView.isAvailable) {
            val surface = textureView.surfaceTexture
            if (surface != null) listener.onSurfaceTextureAvailable(surface, 0, 0)
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }
        textureView.addOnLayoutChangeListener(layoutListener)
        onDispose {
            textureView.removeOnLayoutChangeListener(layoutListener)
            textureView.setSurfaceTextureListener(null)
            service?.camera?.setPreviewSurface(null, null)
        }
    }

    AndroidView(factory = { textureView }, modifier = modifier)
}
