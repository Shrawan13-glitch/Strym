package com.strym.app.capture

/**
 * Picks the encoder resolution for a preset: the largest size the codec
 * supports without exceeding the preset, clamped up to the codec's minimum
 * when the preset is below it, floored to even dimensions as H.264
 * chroma-subsampled encoding requires.
 */
object EncoderSizeSelector {

    data class Range(val min: Int, val max: Int)

    fun choose(width: Int, height: Int, supportedWidth: Range, supportedHeight: Range): Pair<Int, Int> =
        clamp(width, supportedWidth) to clamp(height, supportedHeight)

    private fun clamp(value: Int, range: Range): Int {
        val clamped = value.coerceIn(range.min, range.max)
        val even = clamped and 0x7FFF_FFFE
        return if (even >= range.min) even else range.min
    }
}
