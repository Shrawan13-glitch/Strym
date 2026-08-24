package com.strym.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.strym.app.R
import com.strym.app.StrymApp
import com.strym.app.logging.CoreLogBuffer
import com.strym.app.logging.LogDump
import com.strym.app.service.StreamService
import com.strym.app.session.UiState
import com.strym.app.settings.BroadcastSettings
import com.strym.app.settings.SettingsRepository
import com.strym.app.ui.live.LiveScreen
import com.strym.app.ui.permissions.PermissionGate
import com.strym.app.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun StrymRoot() {
    val service = rememberStreamService()
    val context = LocalContext.current
    val app = context.applicationContext as StrymApp
    val repository = remember(context) { SettingsRepository(context) }
    val settings by repository.settings.collectAsState(initial = BroadcastSettings())
    val scope = rememberCoroutineScope()

    PermissionGate(
        required = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ),
    ) {
        // Keyframe discipline on foreground return: while the app was behind
        // the control center / floating window / app switcher, video may have
        // stalled or recovered from a reconnect. A fresh IDR on resume means
        // viewers resync immediately instead of staring at a spinner.
        LifecycleKeyframeRefresher(service)

        val navController = rememberNavController()
        Scaffold { padding ->
            NavHost(
                navController = navController,
                startDestination = ROUTE_LIVE,
                modifier = Modifier.padding(padding),
            ) {
                composable(ROUTE_LIVE) {
                    LiveScreen(
                        service = service,
                        settings = settings,
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        settings = settings,
                        streaming = streamingState(service).hasSession,
                        onChange = { next ->
                            scope.launch { repository.update { next } }
                        },
                        onBack = { navController.popBackStack() },
                        onReportIssue = { context.startActivity(shareLogDumpIntent(context, app.logBuffer)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun streamingState(service: StreamService?): UiState {
    val fallback = remember { MutableStateFlow(UiState()) }
    val state by (service?.controller?.uiState ?: fallback).collectAsState()
    return state
}

/** Requests a sync frame every time the app returns to the foreground. */
@Composable
private fun LifecycleKeyframeRefresher(service: StreamService?) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, service) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                service?.camera?.requestKeyframe()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Bind to [StreamService] for the lifetime of the composition. Also starts it:
 * the camera only runs for lifecycles at least STARTED, and broadcasting
 * needs the service to survive the UI. On dispose the start is released
 * unless a broadcast is in flight.
 */
@Composable
fun rememberStreamService(): StreamService? {
    val context = LocalContext.current
    val state = remember { MutableStateFlow<StreamService?>(null) }
    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                state.value = (binder as StreamService.LocalBinder).service()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                state.value = null
            }
        }
        val intent = Intent(context, StreamService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            val streaming = state.value?.controller?.uiState?.value?.hasSession == true
            if (!streaming) {
                context.stopService(intent)
            }
            context.unbindService(connection)
        }
    }
    return state.collectAsState().value
}

private const val ROUTE_LIVE = "live"
private const val ROUTE_SETTINGS = "settings"

/** Build a chooser intent sharing the (already redacted) log dump. */
private fun shareLogDumpIntent(context: Context, buffer: CoreLogBuffer): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_share_title))
        putExtra(Intent.EXTRA_TEXT, LogDump.format(buffer))
    }
