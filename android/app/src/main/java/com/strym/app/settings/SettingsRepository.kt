package com.strym.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uniffi.stream_ffi.LatencyMode

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val KEY_SERVER_URL = stringPreferencesKey("server_url")
private val KEY_APP = stringPreferencesKey("app")
private val KEY_STREAM_KEY = stringPreferencesKey("stream_key")
private val KEY_PRESET = stringPreferencesKey("video_preset")
private val KEY_VIDEO_BITRATE = intPreferencesKey("video_bitrate_bps")
private val KEY_LATENCY = stringPreferencesKey("latency_mode")
private val KEY_AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")

/**
 * Persisted broadcast settings. Reads are a cold [Flow]; writes go through
 * [update], which is atomic (`updateData`) and always derives from the
 * current stored value.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<BroadcastSettings> = dataStore.data.map(::read)

    /** The persisted settings, awaited once (service resume path). */
    suspend fun current(): BroadcastSettings = dataStore.data.map(::read).first()

    suspend fun update(transform: (BroadcastSettings) -> BroadcastSettings) {
        dataStore.updateData { prefs ->
            write(transform(read(prefs)))
        }
    }

    private fun read(prefs: Preferences): BroadcastSettings {
        val preset = prefs[KEY_PRESET]
            ?.let { name -> VideoPreset.entries.firstOrNull { it.name == name } }
            ?: VideoPreset.P720_30
        return BroadcastSettings(
            serverUrl = prefs[KEY_SERVER_URL] ?: "",
            app = prefs[KEY_APP] ?: "live",
            streamKey = prefs[KEY_STREAM_KEY] ?: "",
            preset = preset,
            videoBitrateBps = prefs[KEY_VIDEO_BITRATE] ?: preset.defaultBitrateBps,
            latencyMode = prefs[KEY_LATENCY]
                ?.let { name -> LatencyMode.entries.firstOrNull { it.name == name } }
                ?: LatencyMode.BALANCED,
            audioEnabled = prefs[KEY_AUDIO_ENABLED] ?: true,
        )
    }

    private fun write(settings: BroadcastSettings): Preferences =
        mutablePreferencesOf(
            KEY_SERVER_URL to settings.serverUrl,
            KEY_APP to settings.app,
            KEY_STREAM_KEY to settings.streamKey,
            KEY_PRESET to settings.preset.name,
            KEY_VIDEO_BITRATE to settings.videoBitrateBps,
            KEY_LATENCY to settings.latencyMode.name,
            KEY_AUDIO_ENABLED to settings.audioEnabled,
        )
}
