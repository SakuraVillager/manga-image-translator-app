package com.sakuravillager.manga_translator.translation.util

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Minimum distance between two convex polygons.
 * Returns 0 if the polygons overlap.
 */
fun polygonDistance(ptsA: List<PointF>, ptsB: List<PointF>): Float {
    if (ptsA.size < 2 || ptsB.size < 2) return Float.MAX_VALUE

    // Overlap check: if any vertex of one polygon is inside the other
    if (ptsA.size >= 3 && ptsB.size >= 3) {
        if (isPointInConvexPolygon(ptsA[0], ptsB) || isPointInConvexPolygon(ptsB[0], ptsA)) {
            return 0f
        }
    }

    var minDist = Float.MAX_VALUE

    // Edge-to-edge distances
    for (i in ptsA.indices) {
        val j = (i + 1) % ptsA.size
        for (k in ptsB.indices) {
            val l = (k + 1) % ptsB.size
            val d = segmentToSegmentDistance(
                ptsA[i].x, ptsA[i].y, ptsA[j].x, ptsA[j].y,
                ptsB[k].x, ptsB[k].y, ptsB[l].x, ptsB[l].y
            )
            if (d == 0f) return 0f
            if (d < minDist) minDist = d
        }
    }

    // Point-to-edge distances (vertices of A to edges of B)
    for (p in ptsA) {
        for (k in ptsB.indices) {
            val l = (k + 1) % ptsB.size
            val d = pointToSegmentDistance(p.x, p.y, ptsB[k].x, ptsB[k].y, ptsB[l].x, ptsB[l].y)
            if (d < minDist) minDist = d
        }
    }

    // Point-to-edge distances (vertices of B to edges of A)
    for (p in ptsB) {
        for (i in ptsA.indices) {
            val j = (i + 1) % ptsA.size
            val d = pointToSegmentDistance(p.x, p.y, ptsA[i].x, ptsA[i].y, ptsA[j].x, ptsA[j].y)
            if (d < minDist) minDist = d
        }
    }

    return minDist
}

/**
 * Tests whether [point] lies inside a convex [polygon] using cross product signs.
 */
private fun isPointInConvexPolygon(point: PointF, polygon: List<PointF>): Boolean {
    if (polygon.size < 3) return false
    var sign = 0f
    for (i in polygon.indices) {
        val j = (i + 1) % polygon.size
        val cross = (polygon[j].x - polygon[i].x) * (point.y - polygon[i].y) -
            (polygon[j].y - polygon[i].y) * (point.x - polygon[i].x)
        if (cross != 0f) {
            if (sign == 0f) sign = cross
            else if (cross * sign < 0) return false
        }
    }
    return true
}

/**
 * Computes the signed area of a polygon using the shoelace formula.
 * Returns the absolute (positive) value.
 */
fun shoelaceArea(points: List<PointF>): Float {
    if (points.size < 3) return 0f
    var area = 0f
    for (i in points.indices) {
        val j = (i + 1) % points.size
        area += points[i].x * points[j].y
        area -= points[j].x * points[i].y
    }
    return abs(area) / 2f
}

/**
 * Computes the convex hull of a set of points using Andrew's monotone chain algorithm.
 * Returns the hull vertices in counter-clockwise order.
 */
fun convexHull(points: List<PointF>): List<PointF> {
    if (points.size < 3) return points.toList()

    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    val hull = mutableListOf<PointF>()

    // Lower hull
    for (p in sorted) {
        while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) {
            hull.removeAt(hull.size - 1)
        }
        hull.add(p)
    }

    // Upper hull
    val lowerSize = hull.size
    for (i in sorted.indices.reversed()) {
        val p = sorted[i]
        while (hull.size > lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) {
            hull.removeAt(hull.size - 1)
        }
        hull.add(p)
    }

    // Remove duplicate last point (same as first)
    if (hull.size > 1) hull.removeAt(hull.size - 1)
    return hull
}

/**
 * Cross product of vectors (o->a) and (o->b).
 */
private fun cross(o: PointF, a: PointF, b: PointF): Float {
    return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}

/**
 * Perpendicular distance from point P to line segment A-B.
 * If the projection falls outside the segment, returns the distance to the nearest endpoint.
 */
fun pointToSegmentDistance(
    px: Float, py: Float,
    x1: Float, y1: Float,
    x2: Float, y2: Float,
): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0f) {
        // Segment is a point
        val ex = px - x1
        val ey = py - y1
        return sqrt(ex * ex + ey * ey)
    }
    // Projection parameter t, clamped to [0, 1]
    val tClamped = (((px - x1) * dx + (py - y1) * dy) / lenSq).coerceIn(0f, 1f)
    val closestX = x1 + tClamped * dx
    val closestY = y1 + tClamped * dy
    val ex = px - closestX
    val ey = py - closestY
    return sqrt(ex * ex + ey * ey)
}

/**
 * Minimum distance between two line segments.
 * Uses cross-product intersection test followed by point-to-segment fallback.
 */
fun segmentToSegmentDistance(
    x1: Float, y1: Float, x2: Float, y2: Float,
    x3: Float, y3: Float, x4: Float, y4: Float,
): Float {
    // Orientation tests: check if segments straddle each other
    val d1 = cross2(x1, y1, x2, y2, x3, y3)
    val d2 = cross2(x1, y1, x2, y2, x4, y4)
    val d3 = cross2(x3, y3, x4, y4, x1, y1)
    val d4 = cross2(x3, y3, x4, y4, x2, y2)

    // Proper intersection (d1 and d2 opposite signs, d3 and d4 opposite signs)
    if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
        ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    ) {
        return 0f
    }

    // Collinear intersection: check if an endpoint lies on the other segment
    if (d1 == 0f && onSegment(x3, y3, x4, y4, x1, y1)) return 0f
    if (d2 == 0f && onSegment(x3, y3, x4, y4, x2, y2)) return 0f
    if (d3 == 0f && onSegment(x1, y1, x2, y2, x3, y3)) return 0f
    if (d4 == 0f && onSegment(x1, y1, x2, y2, x4, y4)) return 0f

    // Fallback: minimum of four point-to-segment distances
    return min(
        pointToSegmentDistance(x1, y1, x3, y3, x4, y4),
        min(
            pointToSegmentDistance(x2, y2, x3, y3, x4, y4),
            min(
                pointToSegmentDistance(x3, y3, x1, y1, x2, y2),
                pointToSegmentDistance(x4, y4, x1, y1, x2, y2)
            )
        )
    )
}

/**
 * 2D cross product: cross((x2-x1, y2-y1), (x3-x1, y3-y1))
 */
private fun cross2(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    x3: Float, y3: Float,
): Float {
    return (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1)
}

/**
 * Checks whether point (px, py) lies on the segment defined by (x1,y1)-(x2,y2).
 * Assumes the point is collinear with the segment.
 */
private fun onSegment(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    px: Float, py: Float,
): Boolean {
    return px >= min(x1, x2) && px <= max(x1, x2) &&
        py >= min(y1, y2) && py <= max(y1, y2)
}

/**
 * Midpoint of two points.
 */
fun midpoint(a: PointF, b: PointF): PointF {
    return PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}

/**
 * Euclidean distance between two points.
 */
fun euclideanDistance(a: PointF, b: PointF): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * Returns the unit vector in the direction of [vec].
 * If [vec] is the zero vector, returns (0, 0).
 */
fun normalize(vec: PointF): PointF {
    val len = sqrt(vec.x * vec.x + vec.y * vec.y)
    if (len == 0f) return PointF(0f, 0f)
    return PointF(vec.x / len, vec.y / len)
}

/**
 * Dot product of two vectors.
 */
fun dot(a: PointF, b: PointF): Float {
    return a.x * b.x + a.y * b.y
}

/**
 * L2 norm (magnitude) of a vector.
 */
fun norm(vec: PointF): Float {
    return sqrt(vec.x * vec.x + vec.y * vec.y)
}


