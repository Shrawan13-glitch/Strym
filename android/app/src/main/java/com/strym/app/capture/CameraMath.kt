package com.strym.app.capture

import kotlin.math.abs

/**
 * Pure geometry for the GL viewfinder pipeline, kept free of `android.*`
 * classes so the whole file runs in JVM unit tests: the camera output-size
 * choice and the fill/rotate matrix every render target (viewfinder, H.264
 * encoder) applies to the sensor-native camera frame.
 */

/** The largest preview output the camera offers that matches [aspect] without
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
 * Column-major 4x4 model-view-projection for drawing a sensor-native
 * [bufferWidth]x[bufferHeight] frame into a render target [viewWidth]x
 * [viewHeight] pixels: rotated upright by [rotationDegrees] (snapped to
 * 0/90/180/270, clockwise as seen on screen) about the center, then uniformly
 * scaled so the frame covers the whole target — the shorter axis overflows
 * and is center-cropped, never letterboxed or distorted (FILL_CENTER, the
 * stock-camera look). Pass the result as the vertex shader's uMVPMatrix;
 * the SurfaceTexture's own transform stays a separate sampler matrix.
 *
 * Degenerate inputs yield identity rather than NaNs.
 */
fun glFillCropTransform(
    rotationDegrees: Int,
    bufferWidth: Int,
    bufferHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
): FloatArray {
    if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
        return IDENTITY.copyOf()
    }
    val rotation = ((rotationDegrees % 360) + 360) % 360
    val quarterTurns = ((rotation + 45) / 90) % 4
    val swapped = quarterTurns % 2 == 1
    val footprintWidth = if (swapped) bufferHeight else bufferWidth
    val footprintHeight = if (swapped) bufferWidth else bufferHeight
    // Fill (cover): the binding axis decides; the other overflows and crops.
    val fill = maxOf(
        viewWidth.toFloat() / footprintWidth,
        viewHeight.toFloat() / footprintHeight,
    )
    // Exact cos/sin for quarter turns (screen-clockwise in y-down pixel space
    // is the standard [c, -s; s, c] matrix).
    val (cos, sin) = when (quarterTurns) {
        0 -> 1f to 0f
        1 -> 0f to 1f
        2 -> -1f to 0f
        else -> 0f to -1f
    }
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    val bufferCenterX = bufferWidth / 2f
    val bufferCenterY = bufferHeight / 2f
    // Affine coefficients mapping buffer pixels straight into the target's
    // NDC — derived from
    //   ndc = ortho(viewCenter + fill * R(bufferPoint - bufferCenter))
    // with y pointing down in pixel space and up in NDC, so the standard
    // rotation matrix reads as clockwise on screen:
    //   r = R * (p - bc); t = fill * r + vc
    //   ndcX = 2*t.x/vw - 1 ; ndcY = 1 - 2*t.y/vh
    val a = 2f * fill * cos / viewWidth
    val b = -2f * fill * sin / viewWidth
    val d = 2f * fill * sin / viewHeight
    val e = -2f * fill * cos / viewHeight
    val c = 2f * centerX / viewWidth - 1f - a * bufferCenterX - b * bufferCenterY
    val f = 1f - 2f * centerY / viewHeight - d * bufferCenterX - e * bufferCenterY
    return floatArrayOf(
        a, d, 0f, 0f, // column 0
        b, e, 0f, 0f, // column 1
        0f, 0f, 1f, 0f, // column 2
        c, f, 0f, 1f, // column 3 (translation)
    )
}

private val IDENTITY = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

/**
 * Applies [m] (column-major, as produced by [glFillCropTransform]) to the
 * buffer-space point ([x], [y]) and returns its NDC position — test seam for
 * asserting where frame corners land without a GPU.
 */
fun applyTransform(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
    (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])
