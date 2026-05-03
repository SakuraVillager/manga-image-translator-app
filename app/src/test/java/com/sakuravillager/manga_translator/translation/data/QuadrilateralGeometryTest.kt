package com.sakuravillager.manga_translator.translation.data

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for geometry properties of [Quadrilateral].
 *
 * NOTE: Android's mockable jar for unit tests doesn't support
 * PointF(float, float) construction or PointF.equals(). We use reflection
 * to set PointF fields and compare x/y values directly.
 */
class QuadrilateralGeometryTest {

    /** Creates a PointF with specified coordinates via reflection. */
    private fun pt(x: Float, y: Float): PointF {
        val p = PointF()
        PointF::class.java.getField("x").setFloat(p, x)
        PointF::class.java.getField("y").setFloat(p, y)
        return p
    }

    private val squarePoints = listOf(pt(0f, 0f), pt(10f, 0f), pt(10f, 10f), pt(0f, 10f))
    private val square = Quadrilateral(points = squarePoints)

    private val rectPoints = listOf(pt(0f, 0f), pt(20f, 0f), pt(20f, 5f), pt(0f, 5f))
    private val rect = Quadrilateral(points = rectPoints)

    @Test
    fun `test area of square`() {
        assertEquals(100f, square.area, 0.001f)
    }

    @Test
    fun `test area of rectangle`() {
        assertEquals(100f, rect.area, 0.001f)
    }

    @Test
    fun `test center of square`() {
        val c = square.center
        assertEquals(5f, c.x, 0.001f)
        assertEquals(5f, c.y, 0.001f)
    }

    @Test
    fun `test center of rectangle`() {
        val c = rect.center
        assertEquals(10f, c.x, 0.001f)
        assertEquals(2.5f, c.y, 0.001f)
    }

    @Test
    fun `test angle of axis-aligned quad`() {
        assertTrue(square.angle > 0f)
        assertTrue(square.angle < (Math.PI / 2).toFloat())
    }

    @Test
    fun `test aspect ratio of tall rectangle`() {
        val tallRect = Quadrilateral(
            points = listOf(pt(0f, 0f), pt(5f, 0f), pt(5f, 20f), pt(0f, 20f)),
        )
        assertEquals(4f, tallRect.aspectRatio, 0.001f)
    }

    @Test
    fun `test aspect ratio of square is 1`() {
        assertEquals(1f, square.aspectRatio, 0.001f)
    }

    @Test
    fun `test fontSize of square`() {
        assertEquals(10f, square.fontSize, 0.001f)
    }

    @Test
    fun `test fontSize of rectangle`() {
        assertEquals(5f, rect.fontSize, 0.001f)
    }

    @Test
    fun `test sortPoints returns four points`() {
        val shuffled = listOf(pt(0f, 0f), pt(10f, 10f), pt(0f, 10f), pt(10f, 0f))
        val result = Quadrilateral.sortPoints(shuffled)
        assertEquals(4, result.size)
    }

    @Test
    fun `test sortPoints all input points present in output`() {
        val shuffled = listOf(pt(0f, 0f), pt(10f, 10f), pt(0f, 10f), pt(10f, 0f))
        val result = Quadrilateral.sortPoints(shuffled)
        for (p in shuffled) {
            val found = result.any { r -> kotlin.math.abs(r.x - p.x) < 0.001f && kotlin.math.abs(r.y - p.y) < 0.001f }
            assertTrue(found)
        }
    }

    @Test
    fun `test sortPoints first point is top-left`() {
        val shuffled = listOf(pt(10f, 10f), pt(0f, 10f), pt(10f, 0f), pt(0f, 0f))
        val result = Quadrilateral.sortPoints(shuffled)
        assertEquals(0f, result[0].x, 0.001f)
        assertEquals(0f, result[0].y, 0.001f)
    }

    @Test
    fun `test sortPoints returns consistent cyclic order`() {
        val shuffled = listOf(pt(0f, 0f), pt(10f, 10f), pt(0f, 10f), pt(10f, 0f))
        val result = Quadrilateral.sortPoints(shuffled)
        // Expected: TL(0,0), BL(0,10), BR(10,10), TR(10,0)
        assertEquals(0f, result[0].x, 0.001f); assertEquals(0f, result[0].y, 0.001f)   // TL
        assertEquals(0f, result[1].x, 0.001f); assertEquals(10f, result[1].y, 0.001f)  // BL
        assertEquals(10f, result[2].x, 0.001f); assertEquals(10f, result[2].y, 0.001f) // BR
        assertEquals(10f, result[3].x, 0.001f); assertEquals(0f, result[3].y, 0.001f)  // TR
    }

    @Test
    fun `test sortPoints returns same order for already sorted input`() {
        val alreadySorted = listOf(pt(0f, 0f), pt(0f, 10f), pt(10f, 10f), pt(10f, 0f))
        val result = Quadrilateral.sortPoints(alreadySorted)
        for (i in alreadySorted.indices) {
            assertEquals(alreadySorted[i].x, result[i].x, 0.001f)
            assertEquals(alreadySorted[i].y, result[i].y, 0.001f)
        }
    }

    @Test
    fun `test distance AUTO direction returns positive for separated quads`() {
        val q2 = Quadrilateral(
            points = listOf(pt(30f, 0f), pt(40f, 0f), pt(40f, 10f), pt(30f, 10f)),
        )
        val dist = square.distance(q2)
        assertTrue(dist > 0f)
    }

    @Test
    fun `test distance AUTO direction touching quads returns near zero`() {
        val q2 = Quadrilateral(
            points = listOf(pt(10f, 0f), pt(20f, 0f), pt(20f, 10f), pt(10f, 10f)),
        )
        val dist = square.distance(q2)
        assertEquals(0f, dist, 0.001f)
    }

    @Test
    fun `test distance returns FloatMAX VALUE for quads with fewer than 4 points`() {
        val invalid = Quadrilateral(points = listOf(pt(0f, 0f), pt(10f, 0f)))
        assertEquals(Float.MAX_VALUE, invalid.distance(square), 0f)
        assertEquals(Float.MAX_VALUE, square.distance(invalid), 0f)
    }

    @Test
    fun `test center of empty points returns origin`() {
        val empty = Quadrilateral(points = emptyList())
        assertEquals(0f, empty.center.x, 0f)
        assertEquals(0f, empty.center.y, 0f)
    }

    @Test
    fun `test area of empty points returns zero`() {
        val empty = Quadrilateral(points = emptyList())
        assertEquals(0f, empty.area, 0f)
    }

    @Test
    fun `test fontSize of empty points returns zero`() {
        val empty = Quadrilateral(points = emptyList())
        assertEquals(0f, empty.fontSize, 0f)
    }

    @Test
    fun `test aspect ratio of empty points returns zero`() {
        val empty = Quadrilateral(points = emptyList())
        assertEquals(0f, empty.aspectRatio, 0f)
    }

    @Test
    fun `test angle of empty points returns zero`() {
        val empty = Quadrilateral(points = emptyList())
        assertEquals(0f, empty.angle, 0f)
    }
}
