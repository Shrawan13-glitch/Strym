package com.strym.app.capture

import kotlin.math.abs

/**
 * Pure geometry for the GL pipeline, kept free of `android.*`
 * classes so the whole file runs in JVM unit tests: the fill/rotate matrix
 * the H.264 encoder target applies to the sensor-native camera frame, and the
 * SurfaceTexture-matrix classifier that detects HALs which pre-rotate buffer
 * content (transposing ST) and need a half-turn on top of the standard
 * sensor-orientation formula.
 */

/**
 * The rotation correction some camera HALs need on top of the standard
 * `(sensorOrientation - displayRotation)` formula, read off the linear block
 * of the SurfaceTexture transform: a diagonal ±1 matrix (the Android-standard
 * flip family) is exactly what the formula assumes. An anti-diagonal matrix
 * — a transposing ST, observed on some OPPO/OnePlus HALs whose buffers arrive
 * pre-rotated — makes the standard formula overshoot by a half turn.
 * Returns 0 for the standard family, 180 for the transposing one.
 */
fun stQuirkCompensationDegrees(st: FloatArray): Int {
    val diagonal = abs(st[0]) > 0.5f && abs(st[5]) > 0.5f
    val antiDiagonal = abs(st[1]) > 0.5f && abs(st[4]) > 0.5f
    return if (!diagonal && antiDiagonal) 180 else 0
}

/**
 * Column-major 4x4 model-view-projection for drawing a sensor-native
 * [bufferWidth]x[bufferHeight] frame into a render target [viewWidth]x
 * [viewHeight] pixels: rotated upright by [rotationDegrees] (snapped to
 * 0/90/180/270, clockwise as seen on screen) about the center, then uniformly
 * scaled so the frame covers the whole target — the shorter axis overflows
 * and is center-cropped, never letterboxed or distorted (FILL_CENTER, the
 * stock-camera look). Pass the result as the vertex shader's uMVPMatrix,
 * applied to the standard full-screen quad in clip space (±1); the
 * SurfaceTexture's own transform stays a separate sampler matrix.
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
    val d = -2f * fill * sin / viewHeight
    val e = -2f * fill * cos / viewHeight
    val c = 2f * centerX / viewWidth - 1f - a * bufferCenterX - b * bufferCenterY
    val f = 1f - 2f * centerY / viewHeight - d * bufferCenterX - e * bufferCenterY
    // The shader's vertex attribute is the clip-space quad (±1), not buffer
    // pixels: fold the pixel-rect → quad scaling into the matrix, i.e.
    // M' = M · S where pixel p = S(q) = ((q.x+1)/2·bw, (q.y+1)/2·bh).
    val sx = bufferWidth / 2f
    val sy = bufferHeight / 2f
    return floatArrayOf(
        a * sx, d * sx, 0f, 0f, // column 0
        b * sy, e * sy, 0f, 0f, // column 1
        0f, 0f, 1f, 0f, // column 2
        a * sx + b * sy + c, d * sx + e * sy + f, 0f, 1f, // column 3 (translation)
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
 * clip-space quad point ([x], [y]) and returns its NDC position — test seam
 * for asserting where frame corners land without a GPU.
 */
fun applyTransform(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
    (m[0] * x + m[4] * y + m[12]) to (m[1] * x + m[5] * y + m[13])
