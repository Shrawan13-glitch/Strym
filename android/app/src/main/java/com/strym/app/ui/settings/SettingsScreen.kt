package com.strym.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.strym.app.R
import com.strym.app.settings.BroadcastSettings
import com.strym.app.settings.BatteryPrompt
import com.strym.app.settings.StreamAspect
import com.strym.app.settings.VideoPreset
import com.strym.app.ui.live.formatBitrate
import uniffi.stream_ffi.LatencyMode

/**
 * Broadcast settings UI. Controls edit a local draft; nothing persists (and
 * nothing is applied) until the user taps Save — persisting on every
 * keystroke raced with the DataStore round-trip and clobbered typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: BroadcastSettings,
    streaming: Boolean,
    onChange: (BroadcastSettings) -> Unit,
    onBack: () -> Unit,
    onReportIssue: () -> Unit,
) {
    // Local draft; re-initialised only when the persisted settings change
    // (initial load, or after a save round-trips back through the flow).
    var draft by remember(settings) { mutableStateOf(settings) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = draft.serverUrl,
                onValueChange = { draft = draft.copy(serverUrl = it) },
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text(BroadcastSettings.RTMP_SCHEME + "a.rtmp.youtube.com/live2") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.app,
                onValueChange = { draft = draft.copy(app = it) },
                label = { Text(stringResource(R.string.settings_app)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            var keyVisible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = draft.streamKey,
                onValueChange = { draft = draft.copy(streamKey = it) },
                label = { Text(stringResource(R.string.settings_stream_key)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(
                            stringResource(
                                if (keyVisible) R.string.settings_hide_key
                                else R.string.settings_show_key,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.settings_video_preset),
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                VideoPreset.entries.forEachIndexed { index, preset ->
                    SegmentedButton(
                        selected = draft.preset == preset,
                        onClick = {
                            draft = draft.copy(
                                preset = preset,
                                videoBitrateBps = preset.defaultBitrateBps,
                            )
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = VideoPreset.entries.size,
                        ),
                    ) {
                        Text(preset.label)
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_aspect),
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                StreamAspect.entries.forEachIndexed { index, aspect ->
                    SegmentedButton(
                        selected = draft.aspect == aspect,
                        onClick = { draft = draft.copy(aspect = aspect) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = StreamAspect.entries.size,
                        ),
                    ) {
                        Text(aspect.label)
                    }
                }
            }

            Text(
                text = stringResource(R.string.settings_video_bitrate) +
                    ": " + formatBitrate(draft.videoBitrateBps.toDouble()),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = draft.videoBitrateBps.toFloat(),
                onValueChange = { draft = draft.copy(videoBitrateBps = it.toInt()) },
                valueRange = MIN_BITRATE_BPS.toFloat()..MAX_BITRATE_BPS.toFloat(),
                steps = BITRATE_STEPS,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.settings_latency),
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LATENCY_OPTIONS.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = draft.latencyMode == mode,
                        onClick = { draft = draft.copy(latencyMode = mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = LATENCY_OPTIONS.size,
                        ),
                    ) {
                        Text(stringResource(label))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_audio),
                    style = MaterialTheme.typography.titleSmall,
                )
                Switch(
                    checked = draft.audioEnabled,
                    onCheckedChange = { draft = draft.copy(audioEnabled = it) },
                )
            }

            BatteryOptimizationRow()

            Button(
                onClick = { onChange(draft) },
                enabled = draft != settings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_save))
            }

            OutlinedButton(
                onClick = onReportIssue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_report_issue))
            }

            if (streaming) {
                Text(
                    text = stringResource(R.string.settings_applies_next),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

private const val MIN_BITRATE_BPS = 500_000
private const val MAX_BITRATE_BPS = 8_000_000

/** (MAX - MIN) / 100k intervals, minus one — Slider's `steps` counts midpoints. */
private const val BITRATE_STEPS = (MAX_BITRATE_BPS - MIN_BITRATE_BPS) / 100_000 - 1

private val LATENCY_OPTIONS = listOf(
    LatencyMode.AGGRESSIVE to R.string.settings_latency_aggressive,
    LatencyMode.BALANCED to R.string.settings_latency_balanced,
    LatencyMode.LENIENT to R.string.settings_latency_lenient,
)

/**
 * Battery-optimization exemption. Without it, Doze and OEM power managers
 * suspend network access for backgrounded apps — the #1 killer of long mobile
 * broadcasts. The row reflects system state, re-checked every time the screen
 * resumes (i.e. right after returning from the system dialog), and
 * deep-links into the allowlist prompt.
 */
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryPrompt.isExempt(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = BatteryPrompt.isExempt(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_battery_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    if (exempt) R.string.settings_battery_summary_on
                    else R.string.settings_battery_summary_off,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = exempt,
            onCheckedChange = { wanted ->
                if (wanted && !BatteryPrompt.isExempt(context)) {
                    context.startActivity(BatteryPrompt.requestIntent(context))
                }
            },
        )
    }
}
