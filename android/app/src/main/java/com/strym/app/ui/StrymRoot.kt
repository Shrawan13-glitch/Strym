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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    val repository = remember(context) { SettingsRepository(context) }
    val settings by repository.settings.collectAsState(initial = BroadcastSettings())
    val scope = rememberCoroutineScope()

    PermissionGate(
        required = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ),
    ) {
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

/** Bind to [StreamService] for the lifetime of the composition. */
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
        context.bindService(
            Intent(context, StreamService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose {
            context.unbindService(connection)
        }
    }
    return state.collectAsState().value
}

private const val ROUTE_LIVE = "live"
private const val ROUTE_SETTINGS = "settings"
