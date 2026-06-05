package com.sakuravillager.manga_translator.translation.data

import com.sakuravillager.manga_translator.translation.pt
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * Tests for [Quadrilateral] angle consistency.
 *
 * Note: `Quadrilateral.angle` is the angle of the structure vector (midpoint-based),
 * not a direct rotation angle. For a square or axis-aligned quad, this produces
 * π/4 (45°) because it measures the orientation of the quad's internal structure.
 * This differs from `TextBlock.angle` which represents the actual text baseline rotation.
 */
class QuadrilateralAngleTest {

    private val tolerance = 0.1f

    @Test
    fun `horizontal quad has angle in expected range`() {
        // A wide quad (horizontal) has structure angle of π/4 due to midpoint-based computation
        val quad = Quadrilateral(points = listOf(
            pt(0f, 0f), pt(40f, 0f), pt(40f, 10f), pt(0f, 10f),
        ))
        // angle is in (0, π/2) — not near 0 or π for a wide rect
        assertTrue("angle=${quad.angle} should be > 0", quad.angle > 0f)
        assertTrue("angle=${quad.angle} should be < π/2", quad.angle < (PI / 2).toFloat())
    }

    @Test
    fun `vertical quad has angle in expected range`() {
        // A tall quad (vertical) has structure angle in the valid range
        val quad = Quadrilateral(points = listOf(
            pt(0f, 0f), pt(0f, 40f), pt(10f, 40f), pt(10f, 0f),
        ))
        assertTrue("angle=${quad.angle} should be >= 0", quad.angle >= 0f)
    }

    @Test
    fun `angle is in radians not degrees`() {
        // A quad tilted 30° should have angle ≈ π/6 ≈ 0.52 rad, NOT 30 degrees
        val tiltAngle = (PI / 6).toFloat() // 30° in radians
        val cos = kotlin.math.cos(tiltAngle)
        val sin = kotlin.math.sin(tiltAngle)
        // Rotate a horizontal quad by 30°
        val pts = listOf(
            pt(0f, 0f), pt(40f, 0f), pt(40f, 10f), pt(0f, 10f),
        ).map { p ->
            pt(p.x * cos - p.y * sin, p.x * sin + p.y * cos)
        }
        val quad = Quadrilateral(points = pts)

        // Angle should be close to PI/6 in radians (≈ 0.52), not 30 in degrees
        // Allow generous tolerance since structure vector adds complexity
        assertTrue("Angle ${quad.angle} should be near ${PI / 6} rad, not 30 degrees",
            abs(quad.angle - (PI / 6).toFloat()) < tolerance * 3)
    }

    @Test
    fun `angle magnitude is between 0 and PI_2`() {
        // Various orientations should all produce angles in [0, PI/2]
        val quads = listOf(
            listOf(pt(0f, 0f), pt(40f, 0f), pt(40f, 10f), pt(0f, 10f)),
            listOf(pt(0f, 0f), pt(0f, 40f), pt(10f, 40f), pt(10f, 0f)),
            listOf(pt(0f, 0f), pt(40f, 5f), pt(38f, 15f), pt(-2f, 10f)),
        )

        for (pts in quads) {
            val quad = Quadrilateral(points = pts)
            assertTrue("Angle ${quad.angle} should be in [0, PI/2]",
                quad.angle >= 0f && quad.angle <= (PI / 2).toFloat() + tolerance)
        }
    }
}
