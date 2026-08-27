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
        // Fast path: any encoder that directly supports the preset (common case).
        // Try all candidates, not just the first, preferring hardware (OMX.qcom, c2.qti) over software.
        val candidates = list.codecInfos.filter { surfaceAvcEncoder(it) }
            .sortedWith(compareBy(
                { if (it.name.contains("qcom", true) || it.name.contains("qti", true)) 0 else 1 },
                { it.name }
            ))
        for (info in candidates) {
            val fmt = MediaFormat.createVideoFormat(MIME_AVC, presetWidth, presetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            if (runCatching { info.getCapabilitiesForType(MIME_AVC).isFormatSupported(fmt) }.getOrDefault(false)) {
                return Selection(info.name, Size(presetWidth, presetHeight))
            }
        }
        // Fallback: pick the best surface AVC encoder and search for a supported size.
        val fallback = candidates.firstOrNull() ?: return null
        val video = runCatching {
            fallback.getCapabilitiesForType(MIME_AVC).videoCapabilities
        }.getOrNull() ?: return null
        val tester: (Int, Int) -> Boolean = { w, h ->
            runCatching { video.isSizeSupported(w, h) }.getOrDefault(false)
        }
        val (width, height) = EncoderSizeSelector.chooseWithTester(
            presetWidth,
            presetHeight,
            EncoderSizeSelector.Range(video.supportedWidths.lower, video.supportedWidths.upper),
            EncoderSizeSelector.Range(video.supportedHeights.lower, video.supportedHeights.upper),
            tester,
        )
        // Final validation: if even the clamped size is unsupported, try to query supported sizes directly.
        if (!tester(width, height)) {
            // Last resort: ask video capabilities for a size that is supported and closest to preset area.
            val fallbackSize = runCatching {
                // Use the largest supported size <= preset by area, else smallest supported.
                var best: Size? = null
                var bestArea = -1
                // Probe a few aspect-preserving candidates
                val candidatesSizes = listOf(
                    Size(presetWidth, presetHeight),
                    Size((presetWidth and 0x7FFF_FFFE), (presetHeight and 0x7FFF_FFFE)),
                    Size(1280, 720), Size(960, 540), Size(854, 480), Size(640, 360),
                    Size(video.supportedWidths.lower, video.supportedHeights.lower),
                )
                for (s in candidatesSizes) {
                    if (video.isSizeSupported(s.width, s.height)) {
                        val area = s.width * s.height
                        if (area <= presetWidth * presetHeight && area > bestArea) {
                            best = s; bestArea = area
                        }
                    }
                }
                best
            }.getOrNull() ?: return Selection(fallback.name, Size(width, height))
            if (fallbackSize != null) return Selection(fallback.name, fallbackSize)
        }
        return Selection(fallback.name, Size(width, height))
    }

    private fun surfaceAvcEncoder(info: MediaCodecInfo): Boolean {
        if (!info.isEncoder) return false
        val caps = runCatching { info.getCapabilitiesForType(MIME_AVC) }.getOrNull() ?: return false
        return caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    }
}
