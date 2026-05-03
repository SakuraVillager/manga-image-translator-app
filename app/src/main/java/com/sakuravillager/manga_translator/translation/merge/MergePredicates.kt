package com.sakuravillager.manga_translator.translation.merge

import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import kotlin.math.abs

/**
 * Default merge predicate parameters controlling how aggressively
 * quadrilaterals are merged into text regions.
 */
private const val DEFAULT_RATIO = 1.9f
private const val DEFAULT_DISCARD_CONNECTION_GAP = 2.0f
private const val DEFAULT_CHAR_GAP_TOLERANCE = 1.0f
private const val DEFAULT_CHAR_GAP_TOL2 = 3.0f
private const val DEFAULT_FONT_SIZE_RATIO_TOL = 2.0f
private const val DEFAULT_ASPECT_RATIO_TOL = 1.3f

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
    // Skip empty quads
    if (a.area == 0f || b.area == 0f) return false

    // Direction check: if both have explicit directions, they must match
    if (a.direction != TextDirection.AUTO &&
        b.direction != TextDirection.AUTO &&
        a.direction != b.direction
    ) return false

    // Font size similarity: larger / smaller must be within tolerance
    val maxFs = maxOf(a.fontSize, b.fontSize)
    val minFs = minOf(a.fontSize, b.fontSize)
    if (minFs > 0f && maxFs / minFs > fontSizeRatioTol) return false

    // Aspect ratio compatibility
    val arA = a.aspectRatio
    val arB = b.aspectRatio
    if (maxOf(arA, arB) / (minOf(arA, arB) + 0.001f) > aspectRatioTol) return false

    // Distance check: must be close enough
    val d = a.distance(b, ratio)
    if (d > discardConnectionGap) return false

    // For non-AUTO direction: check axis alignment
    if (a.direction != TextDirection.AUTO) {
        val aCenter = a.center
        val bCenter = b.center
        val isHorizontal = a.direction == TextDirection.HORIZONTAL ||
                a.direction == TextDirection.HORIZONTAL_RTL

        if (isHorizontal) {
            // Horizontal text: Y-axis distance should be small (same line)
            val yDist = abs(aCenter.y - bCenter.y) / maxFs
            if (yDist > charGapTolerance) return false
        } else {
            // Vertical text: X-axis distance should be small (same column)
            val xDist = abs(aCenter.x - bCenter.x) / maxFs
            if (xDist > charGapTolerance) return false
        }
    }

    return true
}
