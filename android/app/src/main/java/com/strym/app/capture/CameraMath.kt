package com.strym.app.capture

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure geometry for the Camera2 viewfinder. Kept free of `android.*` classes so
 * the whole file runs in JVM unit tests: preview output-size selection, the
 * rotation/scale transform that makes the sensor-native buffer upright on
 * screen, and the 16:9 crop region shared by the preview + encoder surfaces.
 */

/** How a preview [SurfaceView] must be mapped to display its buffer upright. */
data class PreviewTransform(
    /** Clockwise rotation to apply to the buffer, 0/90/180/270. */
    val rotationDegrees: Int,
    /** Uniform fill scale (≥1): center-crops to fill the view, like a stock camera. */
    val scale: Float,
)

/** An axis-aligned crop inside the sensor's active array, centered. */
data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The largest preview output the camera offers that matches [aspect] without
 * exceeding [maxWidth] wide (quality-first: ties go to the larger area).
 * Falls back to the closest aspect when nothing is in range.
 */
fun choosePreviewSize(
    sizes: List<Pair<Int, Int>>,
    aspect: Float,
    maxWidth: Int = 1920,
): Pair<Int, Int>? {
    if (sizes.isEmpty() || aspect <= 0f) return null
    val candidates = sizes.filter { it.first in 640..maxWidth && it.second >= 480 }
    val pool = if (candidates.isEmpty()) sizes else candidates
    return pool.minWithOrNull(
        compareBy<Pair<Int, Int>> { aspectDiff(it, aspect) }
            .thenByDescending { it.first.toLong() * it.second },
    )
}

private fun aspectDiff(size: Pair<Int, Int>, aspect: Float): Float =
    abs(size.first.toFloat() / size.second - aspect)

/**
 * Map a sensor-native (landscape) buffer onto a [viewWidth]×[viewHeight] screen.
 *
 * The net clockwise rotation is the sensor's mounting orientation corrected for
 * the device's current display rotation; with the buffer rotated by 90/270 its
 * effective footprint swaps width/height. The scale fills the view completely
 * (FILL_CENTER): the larger of the two axis scales, so the shorter axis
 * overflows and is center-cropped rather than letterboxed — the stock-camera
 * look: full screen, upright, never distorted.
 */
fun computePreviewTransform(
    sensorOrientation: Int,
    deviceRotationDegrees: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
): PreviewTransform {
    if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
        return PreviewTransform(0, 1f)
    }
    val rotation = ((sensorOrientation - deviceRotationDegrees) % 360 + 360) % 360
    val footprintWidth = if (rotation % 180 == 90) bufferHeight else bufferWidth
    val footprintHeight = if (rotation % 180 == 90) bufferWidth else bufferHeight
    val scale = maxOf(
        viewWidth.toFloat() / footprintWidth,
        viewHeight.toFloat() / footprintHeight,
    )
    return PreviewTransform(rotation, scale)
}

/**
 * The largest [aspect] (width/height) rectangle centered inside a
 * [containerWidth]×[containerHeight] sensor active array. Both the preview and
 * the encoder feed off the same crop, so neither surface sees distortion.
 */
fun largestCrop(containerWidth: Int, containerHeight: Int, aspect: Float): CropRect {
    if (aspect <= 0f) return CropRect(0, 0, containerWidth, containerHeight)
    val containerAspect = containerWidth.toFloat() / containerHeight
    val (width, height) = if (containerAspect > aspect) {
        (containerHeight * aspect).roundToInt() to containerHeight
    } else {
        containerWidth to (containerWidth / aspect).roundToInt()
    }
    return CropRect(
        x = (containerWidth - width).coerceAtLeast(0) / 2,
        y = (containerHeight - height).coerceAtLeast(0) / 2,
        width = width.coerceIn(0, containerWidth),
        height = height.coerceIn(0, containerHeight),
    )
}
