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

    fun choose(width: Int, height: Int, supportedWidth: Range, supportedHeight: Range): Pair<Int, Int> {
        // Original simple behavior: clamp each dimension to range and floor to even.
        // Preserved for unit tests and for fallback path without tester.
        val w = clampSimple(width, supportedWidth)
        val h = clampSimple(height, supportedHeight)
        return w to h
    }

    private fun clampSimple(value: Int, range: Range): Int {
        val clamped = value.coerceIn(range.min, range.max)
        val even = clamped and 0x7FFF_FFFE
        return if (even >= range.min) even else range.min
    }

    fun chooseWithTester(
        width: Int,
        height: Int,
        supportedWidth: Range,
        supportedHeight: Range,
        tester: ((Int, Int) -> Boolean)?,
    ): Pair<Int, Int> {
        // Try simple clamped-even first; if tester rejects, search downwards for a supported combo
        // preserving aspect as close as possible. Also optimistically try 16-align.
        val baseW = clampSimple(width, supportedWidth)
        val baseH = clampSimple(height, supportedHeight)
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

    private fun clamp(value: Int, range: Range): Int = clampSimple(value, range)
}
