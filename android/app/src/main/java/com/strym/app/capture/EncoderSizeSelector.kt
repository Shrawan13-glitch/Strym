package com.strym.app.capture

/**
 * Picks the encoder resolution for a preset: the largest size the codec
 * supports without exceeding the preset, clamped up to the codec's minimum
 * when the preset is below it, floored to even dimensions as H.264
 * chroma-subsampled encoding requires, and 16-aligned when needed.
 *
 * When a [VideoCapabilities] tester is supplied, candidate sizes are
 * validated via `isSizeSupported` so width/height combos that are
 * individually in-range but jointly illegal are rejected.
 */
object EncoderSizeSelector {

    data class Range(val min: Int, val max: Int)

    fun choose(width: Int, height: Int, supportedWidth: Range, supportedHeight: Range): Pair<Int, Int> =
        chooseWithTester(width, height, supportedWidth, supportedHeight, null)

    fun chooseWithTester(
        width: Int,
        height: Int,
        supportedWidth: Range,
        supportedHeight: Range,
        tester: ((Int, Int) -> Boolean)?,
    ): Pair<Int, Int> {
        // Try clamped-even-16 first; if tester rejects, search downwards for a supported combo
        // preserving aspect as close as possible.
        val baseW = clamp(width, supportedWidth)
        val baseH = clamp(height, supportedHeight)
        if (tester == null || tester(baseW, baseH)) return baseW to baseH
        // Brute-force nearest supported size ≤ preset (step -2 to stay even, -16 to hit hardware alignments).
        // Prefer larger area, then closer aspect.
        val targetAspect = width.toDouble() / height.coerceAtLeast(1)
        var best: Pair<Int, Int>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var w = baseW
        while (w >= supportedWidth.min) {
            var h = baseH
            while (h >= supportedHeight.min) {
                if (tester(w, h)) {
                    // Score: area (bigger better) penalized by aspect deviation and by distance from preset.
                    val area = w * h.toDouble()
                    val aspect = w.toDouble() / h.coerceAtLeast(1)
                    val aspectPenalty = kotlin.math.abs(aspect - targetAspect) * 1000.0
                    val sizePenalty = (width - w) + (height - h).toDouble()
                    val score = area - aspectPenalty - sizePenalty
                    if (score > bestScore) {
                        bestScore = score
                        best = w to h
                    }
                    break // for this w, largest h that works is best
                }
                h -= 2
                // Also try 16-aligned steps for faster convergence on strict hardware
                if (h % 16 != 0 && h - 2 >= supportedHeight.min) continue
            }
            w -= 2
        }
        return best ?: (baseW to baseH)
    }

    private fun clamp(value: Int, range: Range): Int {
        val clamped = value.coerceIn(range.min, range.max)
        // H.264 requires even dimensions; many encoders require 16 alignment for
        // efficiency. Floor to even, then to 16 if that still respects min.
        val even = clamped and 0x7FFF_FFFE
        if (even < range.min) return range.min
        val aligned16 = even and 0x7FFF_FFF0
        return if (aligned16 >= range.min && even - aligned16 < 16) {
            // Prefer 16-aligned when close (within 14 pixels) to improve codec acceptance.
            aligned16
        } else even
    }
}
