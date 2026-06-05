package com.sakuravillager.manga_translator.translation.merge

import android.graphics.PointF
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import com.sakuravillager.manga_translator.translation.pt
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tests for [quadrilateralCanMergeRegion] and [quadrilateralCanMergeRegionCoarse].
 *
 * Verifies that:
 * - Same direction → can merge; opposite → cannot
 * - Very different font sizes → cannot merge
 * - Opposite aspect ratios → cannot merge
 * - Quads far apart → cannot merge
 * - Angle difference within 15° → can merge; beyond → cannot
 *
 * Note: `fontSize`, `angle`, and `aspectRatio` on [Quadrilateral] are computed from
 * `points`. `_direction` is the only parameter we can set directly in the constructor.
 */
class MergePredicatesTest {

    /**
     * Creates a horizontal quad at the given position with the given width/height.
     * `fontSize` = min(width, height) and `angle` ≈ 0 for axis-aligned quads.
     */
    private fun hzQuad(
        x: Float, y: Float, w: Float, h: Float,
        direction: TextDirection = TextDirection.AUTO,
    ): Quadrilateral = Quadrilateral(
        points = listOf(pt(x, y), pt(x + w, y), pt(x + w, y + h), pt(x, y + h)),
        _direction = direction,
    )

    /**
     * Creates a tilted quad to test angle-dependent merge predicates.
     */
    private fun tiltedQuad(
        x: Float, y: Float, w: Float, h: Float,
        angleRad: Float,
        direction: TextDirection = TextDirection.AUTO,
    ): Quadrilateral {
        val cx = x + w / 2f
        val cy = y + h / 2f
        val pts = listOf(
            pt(x, y), pt(x + w, y), pt(x + w, y + h), pt(x, y + h),
        ).map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            pt(cx + dx * cos(angleRad) - dy * sin(angleRad),
               cy + dx * sin(angleRad) + dy * cos(angleRad))
        }
        return Quadrilateral(points = pts, _direction = direction)
    }

    // ── Basic direction agreement ────────────────────────────────────────

    @Test
    fun `same direction AUTO can merge`() {
        val a = hzQuad(0f, 0f, 20f, 10f)
        val b = hzQuad(22f, 0f, 20f, 10f)
        assertTrue(quadrilateralCanMergeRegion(a, b))
    }

    @Test
    fun `explicit opposite directions cannot merge`() {
        val a = hzQuad(0f, 0f, 20f, 10f, direction = TextDirection.HORIZONTAL)
        val b = hzQuad(0f, 0f, 10f, 20f, direction = TextDirection.VERTICAL)
        assertFalse(quadrilateralCanMergeRegion(a, b))
    }

    @Test
    fun `both HORIZONTAL can merge`() {
        val a = hzQuad(0f, 0f, 20f, 10f, direction = TextDirection.HORIZONTAL)
        val b = hzQuad(22f, 0f, 20f, 10f, direction = TextDirection.HORIZONTAL)
        assertTrue(quadrilateralCanMergeRegion(a, b))
    }

    @Test
    fun `both VERTICAL can merge`() {
        val a = hzQuad(0f, 0f, 10f, 20f, direction = TextDirection.VERTICAL)
        val b = hzQuad(0f, 22f, 10f, 20f, direction = TextDirection.VERTICAL)
        assertTrue(quadrilateralCanMergeRegion(a, b))
    }

    // ── Font size ratio ──────────────────────────────────────────────────

    @Test
    fun `very different font sizes cannot merge`() {
        // 10 tall → fontSize=10 (min dimension)
        val a = hzQuad(0f, 0f, 20f, 10f)
        // 30 tall → fontSize=30, ratio=3.0 > fontSizeRatioTol=1.5
        val b = hzQuad(22f, 0f, 20f, 30f)
        assertFalse(quadrilateralCanMergeRegion(a, b))
    }

    @Test
    fun `similar font sizes can merge`() {
        // 10 tall → fontSize=10
        val a = hzQuad(0f, 0f, 20f, 10f)
        // 11 tall → fontSize=11, ratio=1.1 < 1.5
        val b = hzQuad(22f, 0f, 20f, 11f)
        assertTrue(quadrilateralCanMergeRegion(a, b))
    }

    // ── Aspect ratio ─────────────────────────────────────────────────────

    @Test
    fun `opposite aspect ratios cannot merge`() {
        // a is wide (aspect ratio ≈ 5.0), b is not (aspect ratio ≈ 0.2 with opposite orientation)
        // But same direction check: both AUTO → HORIZONTAL or VERTICAL based on points.
        // hzQuad(0,0,50,10) → HORIZONTAL (sortPoints returns non-vertical)
        // hzQuad(60,0,10,50) → VERTICAL (sortPoints returns vertical)
        val a = hzQuad(0f, 0f, 50f, 10f)
        val b = hzQuad(60f, 0f, 10f, 50f)
        // Their directions differ (AUTO resolved differently), so either:
        // - direction mismatch blocks the merge, or
        // - aspect ratio mismatch blocks the merge
        assertFalse(quadrilateralCanMergeRegion(a, b))
    }

    // ── Distance ─────────────────────────────────────────────────────────

    @Test
    fun `quads far apart cannot merge`() {
        val a = hzQuad(0f, 0f, 20f, 10f)
        val b = hzQuad(500f, 500f, 20f, 10f)
        assertFalse(quadrilateralCanMergeRegion(a, b))
    }

    // ── Angle tolerance (for non-axis-aligned quads) ─────────────────────

    @Test
    fun `angle difference within 15 degrees can merge`() {
        val a = tiltedQuad(0f, 0f, 20f, 10f, angleRad = 0f)
        // ~5.7 degrees
        val b = tiltedQuad(22f, 0f, 20f, 10f, angleRad = 0.1f)
        assertTrue("Angle diff 0.1 rad (~5.7°) should be < 15°", quadrilateralCanMergeRegion(a, b))
    }

    @Test
    fun `angle difference beyond 15 degrees cannot merge`() {
        val a = tiltedQuad(0f, 0f, 20f, 10f, angleRad = 0f)
        // ~28.6 degrees
        val b = tiltedQuad(22f, 0f, 20f, 10f, angleRad = 0.5f)
        assertFalse("Angle diff 0.5 rad (~28.6°) should be > 15°", quadrilateralCanMergeRegion(a, b))
    }

    // ── Coarse predicate ─────────────────────────────────────────────────

    @Test
    fun `coarse predicate same direction can merge`() {
        val a = hzQuad(0f, 0f, 20f, 10f, direction = TextDirection.HORIZONTAL)
        val b = hzQuad(22f, 0f, 20f, 10f, direction = TextDirection.HORIZONTAL)
        assertTrue(quadrilateralCanMergeRegionCoarse(a, b))
    }

    @Test
    fun `coarse predicate opposite direction cannot merge`() {
        val a = hzQuad(0f, 0f, 20f, 10f, direction = TextDirection.HORIZONTAL)
        val b = hzQuad(0f, 0f, 10f, 20f, direction = TextDirection.VERTICAL)
        assertFalse(quadrilateralCanMergeRegionCoarse(a, b))
    }

    @Test
    fun `coarse predicate large angle difference cannot merge`() {
        val a = tiltedQuad(0f, 0f, 20f, 10f, angleRad = 0f)
        val b = tiltedQuad(22f, 0f, 20f, 10f, angleRad = 0.5f)
        assertFalse(quadrilateralCanMergeRegionCoarse(a, b))
    }
}