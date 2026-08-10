package com.strym.app.capture

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size

private const val MIME_AVC = "video/avc"

/**
 * Queries the platform for an H.264 encoder and the resolution to feed it.
 *
 * Prefers an encoder that accepts the preset as-is (the common case);
 * otherwise falls back to any surface-capable AVC encoder and clamps to the
 * largest size it supports that does not exceed the preset.
 */
object EncoderCapabilities {

    data class Selection(val codecName: String, val size: Size)

    fun select(presetWidth: Int, presetHeight: Int, bitrateBps: Int): Selection? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val probe = MediaFormat.createVideoFormat(MIME_AVC, presetWidth, presetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        }
        list.findEncoderForFormat(probe)?.let {
            return Selection(it, Size(presetWidth, presetHeight))
        }
        val fallback = list.codecInfos.firstOrNull { info -> surfaceAvcEncoder(info) } ?: return null
        val video = runCatching {
            fallback.getCapabilitiesForType(MIME_AVC).videoCapabilities
        }.getOrNull() ?: return null
        val (width, height) = EncoderSizeSelector.choose(
            presetWidth,
            presetHeight,
            EncoderSizeSelector.Range(video.supportedWidths.lower, video.supportedWidths.upper),
            EncoderSizeSelector.Range(video.supportedHeights.lower, video.supportedHeights.upper),
        )
        return Selection(fallback.name, Size(width, height))
    }

    private fun surfaceAvcEncoder(info: MediaCodecInfo): Boolean {
        if (!info.isEncoder) return false
        val caps = runCatching { info.getCapabilitiesForType(MIME_AVC) }.getOrNull() ?: return false
        return caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }
}
