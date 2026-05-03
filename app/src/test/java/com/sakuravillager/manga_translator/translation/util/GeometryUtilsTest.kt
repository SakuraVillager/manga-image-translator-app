package com.sakuravillager.manga_translator.translation.util

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt as ksqrt

/**
 * JVM unit tests for geometry utility functions in [GeometryUtils].
 *
 * All functions are pure Kotlin math with no Android dependencies
 * beyond PointF (available via SDK jar).
 */
class GeometryUtilsTest {

    // ── polygonDistance ────────────────────────────────────────────────

    @Test
    fun `polygonDistance separated squares returns positive distance`() {
        // Square A: (0,0)-(2,0)-(2,2)-(0,2)
        // Square B: (4,0)-(6,0)-(6,2)-(4,2) — gap of 2 units
        val sqA = listOf(
            PointF(0f, 0f), PointF(2f, 0f), PointF(2f, 2f), PointF(0f, 2f),
        )
        val sqB = listOf(
            PointF(4f, 0f), PointF(6f, 0f), PointF(6f, 2f), PointF(4f, 2f),
        )
        val dist = polygonDistance(sqA, sqB)
        // Minimum distance is from (2,0) to (4,0) = 2
        assertEquals(2f, dist, 0.001f)
    }

    @Test
    fun `polygonDistance overlapping squares returns zero`() {
        // Square A: (0,0)-(3,0)-(3,3)-(0,3)
        // Square B: (1,1)-(4,1)-(4,4)-(1,4) — overlaps in region (1,1)-(3,3)
        val sqA = listOf(
            PointF(0f, 0f), PointF(3f, 0f), PointF(3f, 3f), PointF(0f, 3f),
        )
        val sqB = listOf(
            PointF(1f, 1f), PointF(4f, 1f), PointF(4f, 4f), PointF(1f, 4f),
        )
        val dist = polygonDistance(sqA, sqB)
        assertEquals(0f, dist, 0.001f)
    }

    @Test
    fun `polygonDistance touching squares returns zero`() {
        // Squares touching at x=2 edge
        val sqA = listOf(
            PointF(0f, 0f), PointF(2f, 0f), PointF(2f, 2f), PointF(0f, 2f),
        )
        val sqB = listOf(
            PointF(2f, 0f), PointF(4f, 0f), PointF(4f, 2f), PointF(2f, 2f),
        )
        val dist = polygonDistance(sqA, sqB)
        assertEquals(0f, dist, 0.001f)
    }

    @Test
    fun `polygonDistance with degenerate polygons returns MAX_VALUE`() {
        val line = listOf(PointF(0f, 0f), PointF(1f, 1f))
        val sq = listOf(
            PointF(0f, 0f), PointF(2f, 0f), PointF(2f, 2f), PointF(0f, 2f),
        )
        assertEquals(Float.MAX_VALUE, polygonDistance(line, sq), 0f)
        assertEquals(Float.MAX_VALUE, polygonDistance(sq, line), 0f)
    }

    // ── shoelaceArea ───────────────────────────────────────────────────

    @Test
    fun `shoelaceArea rectangle returns correct area`() {
        // 4x3 rectangle → area = 12
        val rect = listOf(
            PointF(0f, 0f), PointF(4f, 0f), PointF(4f, 3f), PointF(0f, 3f),
        )
        assertEquals(12f, shoelaceArea(rect), 0.001f)
    }

    @Test
    fun `shoelaceArea triangle`() {
        // Right triangle with legs 3 and 4 → area = 6
        val tri = listOf(
            PointF(0f, 0f), PointF(3f, 0f), PointF(0f, 4f),
        )
        assertEquals(6f, shoelaceArea(tri), 0.001f)
    }

    @Test
    fun `shoelaceArea degenerate polygon returns zero`() {
        val line = listOf(PointF(0f, 0f), PointF(1f, 1f))
        assertEquals(0f, shoelaceArea(line), 0f)
    }

    @Test
    fun `shoelaceArea empty polygon returns zero`() {
        assertEquals(0f, shoelaceArea(emptyList()), 0f)
    }

    @Test
    fun `shoelaceArea single point returns zero`() {
        assertEquals(0f, shoelaceArea(listOf(PointF(0f, 0f))), 0f)
    }

    // ── pointToSegmentDistance ──────────────────────────────────────────

    @Test
    fun `pointToSegmentDistance perpendicular point`() {
        // Point (0,2) to horizontal segment (0,0)-(4,0)
        // Perpendicular distance = 2
        val dist = pointToSegmentDistance(0f, 2f, 0f, 0f, 4f, 0f)
        assertEquals(2f, dist, 0.001f)
    }

    @Test
    fun `pointToSegmentDistance endpoint distance`() {
        // Point (5,0) to segment (0,0)-(4,0)
        // Nearest point is endpoint (4,0), distance = 1
        val dist = pointToSegmentDistance(5f, 0f, 0f, 0f, 4f, 0f)
        assertEquals(1f, dist, 0.001f)
    }

    @Test
    fun `pointToSegmentDistance point on segment`() {
        // Point (2,0) on segment (0,0)-(4,0)
        val dist = pointToSegmentDistance(2f, 0f, 0f, 0f, 4f, 0f)
        assertEquals(0f, dist, 0.001f)
    }

    @Test
    fun `pointToSegmentDistance vertical segment`() {
        // Point (3,1) to vertical segment (2,0)-(2,4)
        // Perpendicular distance = 1
        val dist = pointToSegmentDistance(3f, 1f, 2f, 0f, 2f, 4f)
        assertEquals(1f, dist, 0.001f)
    }

    @Test
    fun `pointToSegmentDistance degenerate segment`() {
        // Segment is a single point (0,0)-(0,0)
        val dist = pointToSegmentDistance(3f, 4f, 0f, 0f, 0f, 0f)
        assertEquals(5f, dist, 0.001f)
    }

    // ── convexHull ─────────────────────────────────────────────────────

    @Test
    fun `convexHull square returns four corners`() {
        val pts = listOf(
            PointF(0f, 0f), PointF(1f, 0f), PointF(0f, 1f),
            PointF(1f, 1f), PointF(0.5f, 0.5f), // interior point
        )
        val hull = convexHull(pts)
        // Hull should have 4 vertices (excluding the interior point)
        assertEquals(4, hull.size)
    }

    @Test
    fun `convexHull collinear points returns line endpoints`() {
        val pts = listOf(
            PointF(0f, 0f), PointF(1f, 1f), PointF(2f, 2f), PointF(3f, 3f),
        )
        val hull = convexHull(pts)
        // Collinear points on a line: hull should have 2 points (endpoints)
        assertTrue(hull.size in 2..3)
    }

    @Test
    fun `convexHull fewer than 3 points returns as-is`() {
        val pts = listOf(PointF(0f, 0f), PointF(1f, 1f))
        val hull = convexHull(pts)
        assertEquals(2, hull.size)
    }

    // ── midpoint, euclideanDistance, normalize, dot, norm ──────────────

    @Test
    fun `midpoint of two points`() {
        val m = midpoint(PointF(2f, 4f), PointF(6f, 8f))
        assertEquals(4f, m.x, 0.001f)
        assertEquals(6f, m.y, 0.001f)
    }

    @Test
    fun `euclideanDistance between two points`() {
        // 3-4-5 triangle
        val d = euclideanDistance(PointF(0f, 0f), PointF(3f, 4f))
        assertEquals(5f, d, 0.001f)
    }

    @Test
    fun `normalize of non-zero vector`() {
        val v = PointF(3f, 4f)
        val n = normalize(v)
        assertEquals(1f, sqrt(n.x * n.x + n.y * n.y), 0.001f)
        assertEquals(0.6f, n.x, 0.001f)
        assertEquals(0.8f, n.y, 0.001f)
    }

    @Test
    fun `normalize of zero vector returns origin`() {
        val n = normalize(PointF(0f, 0f))
        assertEquals(0f, n.x, 0f)
        assertEquals(0f, n.y, 0f)
    }

    @Test
    fun `dot product`() {
        val d = dot(PointF(1f, 0f), PointF(0f, 1f))
        assertEquals(0f, d, 0.001f)
    }

    @Test
    fun `dot product parallel vectors`() {
        val d = dot(PointF(2f, 0f), PointF(3f, 0f))
        assertEquals(6f, d, 0.001f)
    }

    @Test
    fun `norm of vector`() {
        val n = norm(PointF(3f, 4f))
        assertEquals(5f, n, 0.001f)
    }

    @Test
    fun `norm of zero vector`() {
        val n = norm(PointF(0f, 0f))
        assertEquals(0f, n, 0f)
    }
}

// Helper for normalize test — not imported, so inline
private fun sqrt(v: Float): Float = kotlin.math.sqrt(v.toDouble()).toFloat()
