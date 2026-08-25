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
}

/**
 * The shape of the stream, as encoded width:height — the same selector a
 * stock camera app offers. The encoder size and the sensor crop follow it;
 * the viewfinder shows a window of the same shape rotated upright for the
 * current hold. Applies at go-live; mid-stream changes wait for the next
 * broadcast.
 */
enum class StreamAspect(val ratio: Float, val label: String) {
    LANDSCAPE_16_9(16f / 9f, "16:9"),
    PORTRAIT_9_16(9f / 16f, "9:16"),
    CLASSIC_4_3(4f / 3f, "4:3"),
    SQUARE_1_1(1f, "1:1"),
    ;

    /**
     * Encoded frame size for this aspect at the preset's short side
     * (720 → 1280x720, 720x1280, 960x720 or 720x720; 1080 likewise).
     * Both dimensions are even, as H.264 chroma subsampling requires.
     */
    fun outputSize(shortSide: Int): Pair<Int, Int> {
        val even = { value: Int -> value and 0x7FFF_FFFE }
        return when {
            ratio > 1f -> even((shortSide * ratio).toInt()) to even(shortSide)
            ratio < 1f -> even(shortSide) to even((shortSide / ratio).toInt())
            else -> even(shortSide) to even(shortSide)
        }
    }
}

data class BroadcastSettings(
    val serverUrl: String = "",
    val app: String = "live",
    val streamKey: String = "",
    val preset: VideoPreset = VideoPreset.P720_30,
    val aspect: StreamAspect = StreamAspect.LANDSCAPE_16_9,
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
