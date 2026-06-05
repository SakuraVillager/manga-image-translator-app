package com.sakuravillager.manga_translator.translation.data

import android.graphics.PointF
import com.sakuravillager.manga_translator.translation.pt
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * JVM unit tests for [TextBlock] angle behavior and geometry parity.
 *
 * Verifies that TextBlock.angle is consistently in RADIANS across:
 * - unrotatedPolygons (rotation by -angle)
 * - minRect (rotation by +angle after unrotation)
 * - direction resolution (aspect ratio → HORIZONTAL vs VERTICAL)
 *
 * Note: Python's TextBlock.angle is in DEGREES, but Kotlin uses radians consistently.
 */
class TextBlockAngleTest {

    private val tolerance = 0.01f

    private fun textBlock(
        lines: List<List<PointF>>,
        angle: Float = 0f,
        direction: TextDirection = TextDirection.AUTO,
    ): TextBlock = TextBlock(
        lines = lines,
        angle = angle,
        _direction = direction,
    )

    // ── Basic angle = 0 identity tests ──────────────────────────────────

    @Test
    fun `angle=0 means unrotatedPolygons returns original lines`() {
        val lines = listOf(listOf(pt(10f, 20f), pt(50f, 20f), pt(50f, 40f), pt(10f, 40f)))
        val block = textBlock(lines, angle = 0f)

        val result = block.unrotatedPolygons
        assertEquals(1, result.size)
        assertEquals(4, result[0].size)
        for (i in lines[0].indices) {
            assertEquals(lines[0][i].x, result[0][i].x, tolerance)
            assertEquals(lines[0][i].y, result[0][i].y, tolerance)
        }
    }

    @Test
    fun `angle=0 means minRect equals bounding box of original lines`() {
        val lines = listOf(listOf(pt(10f, 20f), pt(50f, 20f), pt(50f, 40f), pt(10f, 40f)))
        val block = textBlock(lines, angle = 0f)

        val r = block.minRect
        assertEquals(10f, r.left, tolerance)
        assertEquals(20f, r.top, tolerance)
        assertEquals(50f, r.right, tolerance)
        assertEquals(40f, r.bottom, tolerance)
    }

    @Test
    fun `angle0 horizontal box has aspectRatio greater 1 and direction HORIZONTAL`() {
        // 40 wide, 20 tall
        val lines = listOf(listOf(pt(0f, 0f), pt(40f, 0f), pt(40f, 20f), pt(0f, 20f)))
        val block = textBlock(lines, angle = 0f)

        assertTrue("aspectRatio should be > 1", block.aspectRatio > 1f)
        assertEquals(TextDirection.HORIZONTAL, block.direction)
    }

    @Test
    fun `angle0 vertical box has aspectRatio less 1 and direction VERTICAL`() {
        // 20 wide, 40 tall
        val lines = listOf(listOf(pt(0f, 0f), pt(20f, 0f), pt(20f, 40f), pt(0f, 40f)))
        val block = textBlock(lines, angle = 0f)

        assertTrue("aspectRatio should be < 1", block.aspectRatio < 1f)
        assertEquals(TextDirection.VERTICAL, block.direction)
    }

    // ── Rotation symmetry tests ─────────────────────────────────────────

    @Test
    fun `square rotated by PI_2 keeps same minRect dimensions`() {
        val lines = listOf(listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(0f, 10f)))
        val block = textBlock(lines, angle = (PI / 2).toFloat())

        // A square is isotropic: rotation doesn't change its bounding box dimensions
        val r = block.minRect
        val width = r.right - r.left
        val height = r.bottom - r.top
        assertEquals(width, height, 0.5f)
    }

    @Test
    fun `minRect recovers axis aligned bbox when angle matches rotation`() {
        // A 40x10 rectangle rotated 45° around its center (20, 5)
        val cos45 = kotlin.math.cos(PI / 4).toFloat()
        val sin45 = kotlin.math.sin(PI / 4).toFloat()
        val cx = 20f; val cy = 5f
        fun rotate(x: Float, y: Float) = pt(
            cx + (x - cx) * cos45 - (y - cy) * sin45,
            cy + (x - cx) * sin45 + (y - cy) * cos45,
        )
        val rotatedLines = listOf(listOf(
            rotate(0f, 0f), rotate(40f, 0f), rotate(40f, 10f), rotate(0f, 10f)
        ))
        val block = textBlock(rotatedLines, angle = (PI / 4).toFloat())

        val r = block.minRect
        val width = r.right - r.left
        val height = r.bottom - r.top

        // The axis-aligned bbox of a 40x10 rectangle rotated 45°:
        // width = 40*cos45 + 10*sin45 ≈ 35.4, height = 40*sin45 + 10*cos45 ≈ 35.4
        val expected = 40f * cos45 + 10f * sin45
        assertEquals(expected, width, 0.5f)
        assertEquals(expected, height, 0.5f)
    }

    // ── Round-trip test ─────────────────────────────────────────────────

    @Test
    fun `unrotatedPolygons followed by re-rotation recovers original polygon`() {
        val original = listOf(pt(10f, 20f), pt(50f, 25f), pt(45f, 45f), pt(5f, 40f))
        val angle = (PI / 6).toFloat() // 30 degrees
        val block = textBlock(listOf(original), angle = angle)

        val unrotated = block.unrotatedPolygons[0]

        // Re-rotate: apply rotation by +angle around the same center
        val cx = original.map { it.x }.let { (it.min() + it.max()) / 2f }
        val cy = original.map { it.y }.let { (it.min() + it.max()) / 2f }
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        val recovered = unrotated.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            pt(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
        }

        assertEquals(original.size, recovered.size)
        for (i in original.indices) {
            assertEquals("x coord mismatch at $i", original[i].x, recovered[i].x, 0.01f)
            assertEquals("y coord mismatch at $i", original[i].y, recovered[i].y, 0.01f)
        }
    }

    // ── minRect with angle ≠ 0 ──────────────────────────────────────────

    @Test
    fun `minRect with angle applies rotation to bounding box`() {
        // A 10x10 square rotated 45° around its center (5, 5)
        val cos45 = kotlin.math.cos(PI / 4).toFloat()
        val sin45 = kotlin.math.sin(PI / 4).toFloat()
        val cx = 5f; val cy = 5f
        fun rotate(x: Float, y: Float) = pt(
            cx + (x - cx) * cos45 - (y - cy) * sin45,
            cy + (x - cx) * sin45 + (y - cy) * cos45,
        )
        val rotatedLines = listOf(listOf(
            rotate(0f, 0f), rotate(10f, 0f), rotate(10f, 10f), rotate(0f, 10f)
        ))
        val block = textBlock(rotatedLines, angle = (PI / 4).toFloat())

        val r = block.minRect
        val expectedDiag = 10f * kotlin.math.sqrt(2f)
        assertEquals(expectedDiag, r.right - r.left, 0.5f)
        assertEquals(expectedDiag, r.bottom - r.top, 0.5f)
    }

    // ── Direction with angle ────────────────────────────────────────────

    @Test
    fun `explicit direction overrides aspect ratio`() {
        val lines = listOf(listOf(pt(0f, 0f), pt(40f, 0f), pt(40f, 10f), pt(0f, 10f)))
        // 40x10 box (horizontal aspect ratio), but explicitly set to VERTICAL
        val block = textBlock(lines, angle = 0f, direction = TextDirection.VERTICAL)

        assertEquals(TextDirection.VERTICAL, block.direction)
    }
}
