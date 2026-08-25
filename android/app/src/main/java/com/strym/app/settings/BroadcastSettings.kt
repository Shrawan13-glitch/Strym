package com.strym.app.settings

import uniffi.stream_ffi.LatencyMode

enum class VideoPreset(
    val width: Int,
    val height: Int,
    val framerate: Double,
    val defaultBitrateBps: Int,
    val label: String,
) {
    P720_30(1280, 720, 30.0, 2_500_000, "720p30"),
    P1080_30(1920, 1080, 30.0, 4_500_000, "1080p30"),
    ;

    /**
     * Encoded frame size for the device's hold at go-live — landscape keeps
     * [width]x[height], portrait swaps to height x width (e.g. 720x1280), the
     * same one-question decision every stock camera makes. The GL pipeline
     * uprights the sensor pixels into this canvas, so both shapes stream
     * genuinely upright.
     */
    fun outputSize(portrait: Boolean): Pair<Int, Int> =
        if (portrait) height to width else width to height
}

data class BroadcastSettings(
    val serverUrl: String = "",
    val app: String = "live",
    val streamKey: String = "",
    val preset: VideoPreset = VideoPreset.P720_30,
    val videoBitrateBps: Int = VideoPreset.P720_30.defaultBitrateBps,
    val latencyMode: LatencyMode = LatencyMode.BALANCED,
    val audioEnabled: Boolean = true,
) {
    val canStart: Boolean
        get() = serverUrl.startsWith(RTMP_SCHEME) &&
            serverUrl.removePrefix(RTMP_SCHEME).isNotBlank() &&
            app.isNotBlank() &&
            streamKey.isNotBlank()

    companion object {
        const val RTMP_SCHEME = "rtmp://"
        const val AUDIO_BITRATE_BPS = 128_000
        const val AUDIO_SAMPLE_RATE_HZ = 48_000
    }
}
