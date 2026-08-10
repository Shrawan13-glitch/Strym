package com.strym.app.ui.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.strym.app.R
import com.strym.app.settings.BroadcastSettings
import com.strym.app.settings.VideoPreset
import com.strym.app.ui.live.formatBitrate
import uniffi.stream_ffi.LatencyMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: BroadcastSettings,
    streaming: Boolean,
    onChange: (BroadcastSettings) -> Unit,
    onBack: () -> Unit,
) {
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
                value = settings.serverUrl,
                onValueChange = { onChange(settings.copy(serverUrl = it)) },
                label = { Text(stringResource(R.string.settings_server_url)) },
                placeholder = { Text(BroadcastSettings.RTMP_SCHEME + "a.rtmp.youtube.com/live2") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = settings.app,
                onValueChange = { onChange(settings.copy(app = it)) },
                label = { Text(stringResource(R.string.settings_app)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            var keyVisible by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = settings.streamKey,
                onValueChange = { onChange(settings.copy(streamKey = it)) },
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
                        selected = settings.preset == preset,
                        onClick = {
                            onChange(
                                settings.copy(
                                    preset = preset,
                                    videoBitrateBps = preset.defaultBitrateBps,
                                ),
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
                text = stringResource(R.string.settings_video_bitrate) +
                    ": " + formatBitrate(settings.videoBitrateBps.toDouble()),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = settings.videoBitrateBps.toFloat(),
                onValueChange = { onChange(settings.copy(videoBitrateBps = it.toInt())) },
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
                        selected = settings.latencyMode == mode,
                        onClick = { onChange(settings.copy(latencyMode = mode)) },
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
                    checked = settings.audioEnabled,
                    onCheckedChange = { onChange(settings.copy(audioEnabled = it)) },
                )
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
