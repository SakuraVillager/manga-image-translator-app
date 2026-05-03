package com.sakuravillager.manga_translator.translation.data

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class Quadrilateral(
    val points: List<PointF>,
    val text: String = "",
    val probability: Float = 0f,
    val direction: TextDirection = TextDirection.AUTO,
    val fgColor: Int? = null,
    val bgColor: Int? = null,
) {
    private fun mid(a: PointF, b: PointF) = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    val structure: List<PointF> get() {
        if (points.size < 4) return emptyList()
        val v1 = mid(points[0], points[1])  // top edge midpoint
        val v2 = mid(points[2], points[3])  // bottom edge midpoint
        val v3 = mid(points[1], points[2])  // right edge midpoint
        val v4 = mid(points[3], points[0])  // left edge midpoint
        return listOf(v1, v2, v3, v4)
    }

    val center: PointF get() {
        if (points.isEmpty()) return PointF()
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        return PointF(cx, cy)
    }

    val boundingBox: RectF get() {
        if (points.isEmpty()) return RectF()
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val maxX = points.maxOf { it.x }
        val maxY = points.maxOf { it.y }
        return RectF(minX, minY, maxX, maxY)
    }

    val area: Float get() {
        if (points.size < 4) return 0f
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += points[i].x * points[j].y
            a -= points[j].x * points[i].y
        }
        return abs(a) / 2f
    }

    val angle: Float get() {
        val s = structure
        if (s.size < 4) return 0f
        val vx = s[2].x - s[0].x
        val vy = s[2].y - s[0].y
        val len = sqrt(vx * vx + vy * vy)
        if (len == 0f) return 0f
        return acos((vx / len).toDouble()).toFloat()
    }

    val aspectRatio: Float get() {
        val s = structure
        if (s.size < 4) return 0f
        val v1x = s[1].x - s[0].x
        val v1y = s[1].y - s[0].y
        val v2x = s[2].x - s[3].x
        val v2y = s[2].y - s[3].y
        val norm1 = sqrt(v1x * v1x + v1y * v1y)
        val norm2 = sqrt(v2x * v2x + v2y * v2y)
        return if (norm1 == 0f) 0f else norm2 / norm1
    }

    val fontSize: Float get() {
        val s = structure
        if (s.size < 4) return 0f
        val dx1 = s[1].x - s[0].x
        val dy1 = s[1].y - s[0].y
        val dx2 = s[2].x - s[3].x
        val dy2 = s[2].y - s[3].y
        return minOf(
            sqrt(dx1 * dx1 + dy1 * dy1),
            sqrt(dx2 * dx2 + dy2 * dy2)
        )
    }

    fun distance(other: Quadrilateral, rho: Float = 0.5f): Float {
        if (points.size < 4 || other.points.size < 4) return Float.MAX_VALUE

        return if (direction == TextDirection.AUTO) {
            var minDistSq = Float.MAX_VALUE
            for (p1 in points) {
                for (p2 in other.points) {
                    val dx = p1.x - p2.x
                    val dy = p1.y - p2.y
                    val distSq = dx * dx + dy * dy
                    if (distSq < minDistSq) minDistSq = distSq
                }
            }
            val minDist = sqrt(minDistSq)
            val denom = maxOf(fontSize, other.fontSize, 1f) * rho
            minDist / denom
        } else {
            val allPoints = points + other.points
            val hullArea = convexHullArea(allPoints)
            val avgFontSize = (fontSize + other.fontSize) / 2f
            if (avgFontSize == 0f) return 0f
            hullArea / (avgFontSize * rho)
        }
    }

    fun getTransformedRegion(
        bitmap: Bitmap,
        direction: TextDirection,
        textHeight: Int,
    ): Bitmap {
        if (points.size < 4 || textHeight <= 0) return bitmap

        val srcPts = sortPoints(points)
        val w = (aspectRatio * textHeight).toInt().coerceAtLeast(1)
        val h = textHeight

        val srcMat = Mat(4, 2, CvType.CV_32F)
        val dstMat = Mat(4, 2, CvType.CV_32F)

        try {
            srcMat.put(0, 0, floatArrayOf(
                srcPts[0].x, srcPts[0].y,
                srcPts[1].x, srcPts[1].y,
                srcPts[2].x, srcPts[2].y,
                srcPts[3].x, srcPts[3].y,
            ))

            if (direction == TextDirection.VERTICAL) {
                dstMat.put(0, 0, floatArrayOf(
                    0f, 0f,
                    h.toFloat(), 0f,
                    h.toFloat(), w.toFloat(),
                    0f, w.toFloat(),
                ))
            } else {
                dstMat.put(0, 0, floatArrayOf(
                    0f, 0f,
                    w.toFloat(), 0f,
                    w.toFloat(), h.toFloat(),
                    0f, h.toFloat(),
                ))
            }

            val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
            val srcBitmapMat = Mat()
            Utils.bitmapToMat(bitmap, srcBitmapMat)

            val outW = if (direction == TextDirection.VERTICAL) h else w
            val outH = if (direction == TextDirection.VERTICAL) w else h

            val warped = Mat()
            Imgproc.warpPerspective(
                srcBitmapMat, warped, transform,
                Size(outW.toDouble(), outH.toDouble())
            )

            srcBitmapMat.release()
            transform.release()

            if (direction == TextDirection.VERTICAL) {
                val rotated = Mat()
                Core.rotate(warped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                warped.release()
                val result = Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rotated, result)
                rotated.release()
                return result
            }

            val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, result)
            warped.release()
            return result
        } finally {
            srcMat.release()
            dstMat.release()
        }
    }

    companion object {
        fun sortPoints(pts: List<PointF>): List<PointF> {
            if (pts.size != 4) return pts

            val cx = pts.map { it.x.toDouble() }.average()
            val cy = pts.map { it.y.toDouble() }.average()

            // Sort clockwise by angle from centroid
            val sorted = pts.sortedBy { p ->
                -atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())
            }

            // Rotate so top-left (minimum x + y) is first
            val tlIdx = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
            return if (tlIdx == 0) sorted
            else sorted.subList(tlIdx, 4) + sorted.subList(0, tlIdx)
        }
    }
}

private fun convexHullArea(pts: List<PointF>): Float {
    if (pts.size < 3) return 0f

    val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
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

    if (hull.size > 1) hull.removeAt(hull.size - 1)
    if (hull.size < 3) return 0f

    var area = 0f
    for (i in 0 until hull.size) {
        val j = (i + 1) % hull.size
        area += hull[i].x * hull[j].y - hull[j].x * hull[i].y
    }
    return abs(area) / 2f
}

private fun cross(o: PointF, a: PointF, b: PointF): Float {
    return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}
