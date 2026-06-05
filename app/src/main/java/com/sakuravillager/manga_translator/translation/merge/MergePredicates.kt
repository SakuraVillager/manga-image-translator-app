package com.sakuravillager.manga_translator.translation.merge

import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import kotlin.math.PI
import kotlin.math.abs

/**
 * Default merge predicate parameters controlling how aggressively
 * quadrilaterals are merged into text regions.
 */
private const val DEFAULT_RATIO = 1.9f
private const val DEFAULT_DISCARD_CONNECTION_GAP = 2.0f
private const val DEFAULT_CHAR_GAP_TOLERANCE = 0.6f
private const val DEFAULT_CHAR_GAP_TOL2 = 1.5f
private const val DEFAULT_FONT_SIZE_RATIO_TOL = 1.5f
private const val DEFAULT_ASPECT_RATIO_TOL = 2.0f

/**
 * Determines whether two quadrilaterals can be merged into the same text region.
 *
 * Implements the merge predicate logic from the Python reference
 * (generic.py quadrilateral_can_merge_region), ported to Kotlin.
 *
 * @param a First quadrilateral
 * @param b Second quadrilateral
 * @param ratio Aspect ratio scaling factor
 * @param discardConnectionGap Maximum allowed gap between quads
 * @param charGapTolerance Maximum distance (in font-size units) for same-line quads
 * @param charGapTol2 Alternative gap tolerance (currently unused but reserved)
 * @param fontSizeRatioTol Maximum ratio between font sizes (larger/smaller)
 * @param aspectRatioTol Maximum ratio between aspect ratios
 * @return true if the two quadrilaterals can be merged
 */
fun quadrilateralCanMergeRegion(
    a: Quadrilateral,
    b: Quadrilateral,
    ratio: Float = DEFAULT_RATIO,
    discardConnectionGap: Float = DEFAULT_DISCARD_CONNECTION_GAP,
    charGapTolerance: Float = DEFAULT_CHAR_GAP_TOLERANCE,
    charGapTol2: Float = DEFAULT_CHAR_GAP_TOL2,
    fontSizeRatioTol: Float = DEFAULT_FONT_SIZE_RATIO_TOL,
    aspectRatioTol: Float = DEFAULT_ASPECT_RATIO_TOL,
): Boolean {
    if (a.area == 0f || b.area == 0f) return false

    if (a.direction != TextDirection.AUTO &&
        b.direction != TextDirection.AUTO &&
        a.direction != b.direction
    ) return false

    val charSize = minOf(a.fontSize, b.fontSize).coerceAtLeast(1f)
    val maxFs = maxOf(a.fontSize, b.fontSize)
    val minFs = minOf(a.fontSize, b.fontSize).coerceAtLeast(1f)
    if (maxFs / charSize > fontSizeRatioTol) return false

    val arA = a.aspectRatio
    val arB = b.aspectRatio
    if (arA > aspectRatioTol && arB < 1f / aspectRatioTol) return false
    if (arB > aspectRatioTol && arA < 1f / aspectRatioTol) return false

    val dist = a.polyDistance(b)
    if (dist > discardConnectionGap * charSize) return false

    val boxA = a.aabb
    val boxB = b.aabb
    val x1 = boxA.left
    val y1 = boxA.top
    val w1 = boxA.width()
    val h1 = boxA.height()
    val x2 = boxB.left
    val y2 = boxB.top
    val w2 = boxB.width()
    val h2 = boxB.height()

    if (a.isApproximateAxisAligned && b.isApproximateAxisAligned) {
        if (dist < charSize * charGapTolerance) {
            if (abs((x1 + w1 / 2f) - (x2 + w2 / 2f)) < charGapTol2) return true
            if (w1 > h1 * ratio && h2 > w2 * ratio) return false
            if (w2 > h2 * ratio && h1 > w1 * ratio) return false
            return if (w1 > h1 * ratio || w2 > h2 * ratio) {
                abs(x1 - x2) < charSize * charGapTol2 ||
                    abs((x1 + w1) - (x2 + w2)) < charSize * charGapTol2
            } else if (h1 > w1 * ratio || h2 > w2 * ratio) {
                abs(y1 - y2) < charSize * charGapTol2 ||
                    abs((y1 + h1) - (y2 + h2)) < charSize * charGapTol2
            } else {
                false
            }
        }
        return false
    }

    // Angle tolerance: 15 degrees converted to radians (matches Python `15 * np.pi / 180`).
    // Two quads can only merge if their text baseline angles are within this tolerance.
    if (abs(a.angle - b.angle) < 15f * PI.toFloat() / 180f) {
        val fs = minOf(a.fontSize, b.fontSize).coerceAtLeast(1f)
        if (a.polyDistance(b) > fs * charGapTol2) return false
        if (abs(a.fontSize - b.fontSize) / fs > 0.25f) return false
        return true
    }

    return false
}

/**
 * Coarse merge predicate: checks only direction, angle, font size ratio, and polygon distance.
 * Simpler and faster than [quadrilateralCanMergeRegion], used for initial grouping.
 *
 * Ported from Python `quadrilateral_can_merge_region_coarse` (generic.py L700-714).
 */
fun quadrilateralCanMergeRegionCoarse(
    a: Quadrilateral,
    b: Quadrilateral,
    discardConnectionGap: Float = 2f,
    fontSizeRatioTol: Float = 0.7f,
): Boolean {
    if (a.direction != TextDirection.AUTO &&
        b.direction != TextDirection.AUTO &&
        a.direction != b.direction
    ) return false

    if (abs(a.angle - b.angle) > 15f * PI.toFloat() / 180f) return false

    val fsMax = maxOf(a.fontSize, b.fontSize)
    val fsMin = minOf(a.fontSize, b.fontSize).coerceAtLeast(1f)
    if (abs(fsMax - fsMin) / fsMin > fontSizeRatioTol) return false

    val dist = a.polyDistance(b)
    return dist <= discardConnectionGap * maxOf(a.fontSize, b.fontSize)
}
