package com.sakuravillager.manga_translator.translation.data

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.translation.util.polygonDistance
import com.sakuravillager.manga_translator.translation.util.euclideanDistance
import com.sakuravillager.manga_translator.translation.util.pointToSegmentDistance
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
import android.util.Log
import java.io.File
import java.io.FileOutputStream

data class Quadrilateral(
    val points: List<PointF>,
    val text: String = "",
    val probability: Float = 0f,
    private val _direction: TextDirection = TextDirection.AUTO,
    val sourceIndex: Int? = null,
    val readingOrderIndex: Int? = null,
    val fgColor: Int? = null,
    val bgColor: Int? = null,
) {
    /** Direction of this quadrilateral. When AUTO, determined by sortPoints (matching Python sort_pnts). */
    val direction: TextDirection
        get() {
            if (_direction != TextDirection.AUTO) return _direction
            if (points.size != 4) return TextDirection.AUTO
            val (_, isVertical) = sortPoints(points)
            return if (isVertical) TextDirection.VERTICAL else TextDirection.HORIZONTAL
        }
    private fun pointF(x: Float, y: Float): PointF {
        return PointF().apply {
            this.x = x
            this.y = y
        }
    }

    private fun mid(a: PointF, b: PointF) = pointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    val structure: List<PointF> get() {
        if (points.size < 4) return emptyList()
        // Sort points to [TL, TR, BR, BL] (clockwise), matching Python sort_pnts.
        val (p, _) = sortPoints(points)
        val v1 = mid(p[0], p[1])  // TL+TR = top edge midpoint
        val v2 = mid(p[2], p[3])  // BR+BL = bottom edge midpoint
        val v3 = mid(p[1], p[2])  // TR+BR = right edge midpoint
        val v4 = mid(p[3], p[0])  // BL+TL = left edge midpoint
        return listOf(v1, v2, v3, v4)
    }

    val center: PointF get() {
        if (points.isEmpty()) return PointF()
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        return pointF(cx, cy)
    }

    val boundingBox: RectF get() {
        if (points.isEmpty()) return RectF()
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val maxX = points.maxOf { it.x }
        val maxY = points.maxOf { it.y }
        return RectF(minX, minY, maxX, maxY)
    }

    val aabb: RectF get() = boundingBox

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
        return if (norm2 == 0f) 0f else norm1 / norm2
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

    val isApproximateAxisAligned: Boolean get() {
        val s = structure
        if (s.size < 4) return false

        val topToBottom = pointF(s[1].x - s[0].x, s[1].y - s[0].y)
        val rightToLeft = pointF(s[2].x - s[3].x, s[2].y - s[3].y)

        fun isNearAxis(vec: PointF): Boolean {
            val len = sqrt(vec.x * vec.x + vec.y * vec.y)
            if (len == 0f) return false
            val unitX = vec.x / len
            val unitY = vec.y / len
            return abs(unitX) < 0.05f || abs(unitY) < 0.05f
        }

        return isNearAxis(topToBottom) || isNearAxis(rightToLeft)
    }

    val isAxisAligned: Boolean get() {
        val s = structure
        if (s.size < 4) return false
        val v1x = s[1].x - s[0].x; val v1y = s[1].y - s[0].y
        val v2x = s[2].x - s[3].x; val v2y = s[2].y - s[3].y
        val norm1 = sqrt(v1x * v1x + v1y * v1y)
        val norm2 = sqrt(v2x * v2x + v2y * v2y)
        if (norm1 == 0f || norm2 == 0f) return false
        val u1x = v1x / norm1; val u1y = v1y / norm1
        val u2x = v2x / norm2; val u2y = v2y / norm2
        return (abs(u1x) < 0.01f || abs(u1y) < 0.01f) && (abs(u2x) < 0.01f || abs(u2y) < 0.01f)
    }

    /** Direction cosine of the main axis (dot with horizontal unit vector). Matches Python `cosangle` (generic.py L510-515). */
    val cosangle: Float get() {
        val s = structure
        if (s.size < 4) return 0f
        val vx = s[1].x - s[0].x; val vy = s[1].y - s[0].y
        val len = sqrt(vx * vx + vy * vy)
        if (len == 0f) return 0f
        return vx / len
    }

    /** Minimum distance from a point to this quadrilateral (edges + vertices). Matches Python `distance_to_point()` (generic.py L525-530). */
    fun distanceToPoint(p: PointF): Float {
        if (points.size < 4) return Float.MAX_VALUE
        var d = Float.MAX_VALUE
        for (i in 0 until 4) {
            val p1 = points[i]
            val p2 = points[(i + 1) % 4]
            d = minOf(d, euclideanDistance(p1, p),
                pointToSegmentDistance(p.x, p.y, p1.x, p1.y, p2.x, p2.y))
        }
        return d
    }

    fun polyDistance(other: Quadrilateral): Float {
        return polygonDistance(points, other.points)
    }

    fun distance(other: Quadrilateral, rho: Float = 0.5f): Float {
        if (points.size < 4 || other.points.size < 4) return Float.MAX_VALUE

        return if (_direction == TextDirection.AUTO) {
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
        debugRoot: File? = null,
    ): Bitmap {
        if (points.size < 4 || textHeight <= 0) {
            return Bitmap.createBitmap(textHeight.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        // sortPoints returns [TL, TR, BR, BL] (clockwise), matching Python sort_pnts.
        val (sorted, _) = sortPoints(points)
        val s = sorted.map { PointF(it.x, it.y) }.toMutableList()  // [TL, TR, BR, BL]
        val srcRect = boundingBox
        val imWidth = bitmap.width
        val imHeight = bitmap.height

        val x1 = srcRect.left.toInt().coerceIn(0, imWidth)
        val y1 = srcRect.top.toInt().coerceIn(0, imHeight)
        val x2 = srcRect.right.toInt().coerceIn(0, imWidth)
        val y2 = srcRect.bottom.toInt().coerceIn(0, imHeight)
        if (x2 <= x1 || y2 <= y1) {
            return Bitmap.createBitmap(textHeight.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        val cropped = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
        // Save pre-warp crop for debugging if requested
        try {
            if (debugRoot != null) {
                debugRoot.mkdirs()
                val preFile = File(debugRoot, "quad_pre_${x1}_${y1}_${System.currentTimeMillis()}.png")
                FileOutputStream(preFile).use { out -> cropped.compress(Bitmap.CompressFormat.PNG, 90, out) }
                Log.d("Quadrilateral", "Saved pre-warp crop: ${preFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w("Quadrilateral", "Failed to save pre-warp crop: ${e.message}")
        }
        for (pt in s) {
            pt.x -= x1.toFloat()
            pt.y -= y1.toFloat()
        }

        // s = [TL, TR, BR, BL] clockwise
        val midTop = PointF((s[0].x + s[1].x) / 2f, (s[0].y + s[1].y) / 2f)     // TL+TR
        val midBottom = PointF((s[2].x + s[3].x) / 2f, (s[2].y + s[3].y) / 2f)  // BR+BL
        val midRight = PointF((s[1].x + s[2].x) / 2f, (s[1].y + s[2].y) / 2f)    // TR+BR
        val midLeft = PointF((s[0].x + s[3].x) / 2f, (s[0].y + s[3].y) / 2f)     // TL+BL
        val vecVx = midBottom.x - midTop.x
        val vecVy = midBottom.y - midTop.y
        val vecHx = midRight.x - midLeft.x
        val vecHy = midRight.y - midLeft.y
        val normV = kotlin.math.sqrt(vecVx * vecVx + vecVy * vecVy)
        val normH = kotlin.math.sqrt(vecHx * vecHx + vecHy * vecHy)
        if (normV <= 0f || normH <= 0f) {
            return Bitmap.createBitmap(textHeight.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        val ratio = normV / normH
        val (dstW, dstH) = transformedRegionSize(ratio, direction, textHeight)
        val useVertical = direction == TextDirection.VERTICAL
        AppLogger.i("Quadrilateral", "getTR: dir=$direction useV=$useVertical normV=$normV normH=$normH ratio=$ratio dstW=$dstW dstH=$dstH")

        val srcMat = Mat(4, 2, CvType.CV_32F)
        val dstMat = Mat(4, 2, CvType.CV_32F)
        val cropMat = Mat()
        val warped = Mat()
        try {
            srcMat.put(0, 0, floatArrayOf(
                s[0].x, s[0].y,
                s[1].x, s[1].y,
                s[2].x, s[2].y,
                s[3].x, s[3].y,
            ))
            dstMat.put(0, 0, floatArrayOf(
                0f, 0f,
                (dstW - 1).toFloat(), 0f,
                (dstW - 1).toFloat(), (dstH - 1).toFloat(),
                0f, (dstH - 1).toFloat(),
            ))

            Utils.bitmapToMat(cropped, cropMat)
            val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
            Imgproc.warpPerspective(cropMat, warped, transform, Size(dstW.toDouble(), dstH.toDouble()))
            transform.release()

            val output = if (useVertical) {
                val rotated = Mat()
                Core.rotate(warped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                warped.release()
                val result = Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rotated, result)
                rotated.release()
                // Save post-warp for debugging
                try {
                    if (debugRoot != null) {
                        val postFile = File(debugRoot, "quad_post_${x1}_${y1}_${System.currentTimeMillis()}.png")
                        FileOutputStream(postFile).use { out -> result.compress(Bitmap.CompressFormat.PNG, 90, out) }
                        Log.d("Quadrilateral", "Saved post-warp crop: ${postFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.w("Quadrilateral", "Failed to save post-warp crop: ${e.message}")
                }
                result
            } else {
                val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, result)
                warped.release()
                try {
                    if (debugRoot != null) {
                        val postFile = File(debugRoot, "quad_post_${x1}_${y1}_${System.currentTimeMillis()}.png")
                        FileOutputStream(postFile).use { out -> result.compress(Bitmap.CompressFormat.PNG, 90, out) }
                        Log.d("Quadrilateral", "Saved post-warp crop: ${postFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.w("Quadrilateral", "Failed to save post-warp crop: ${e.message}")
                }
                result
            }

            // Log src/dst points for pixel-level inspection
            try {
                val srcPtsStr = "src=[(${s[0].x},${s[0].y}),(${s[1].x},${s[1].y}),(${s[2].x},${s[2].y}),(${s[3].x},${s[3].y})]"
                val dstPtsStr = "dst=[(0,0),(${dstW - 1},0),(${dstW - 1},${dstH - 1}),(0,${dstH - 1})]"
                Log.d("Quadrilateral", "Transformed region src/dst: $srcPtsStr -> $dstPtsStr, dstW=$dstW, dstH=$dstH")
            } catch (e: Exception) {
                // ignore logging failures
            }

            return output
        } finally {
            srcMat.release()
            dstMat.release()
            cropMat.release()
            if (!warped.empty()) warped.release()
        }
    }

    companion object {
        fun transformedRegionSize(
            ratio: Float,
            direction: TextDirection,
            textHeight: Int,
        ): Pair<Int, Int> {
            val safeHeight = textHeight.coerceAtLeast(1)
            val safeRatio = ratio.coerceAtLeast(0.0001f)
            return if (direction == TextDirection.VERTICAL) {
                maxOf(safeHeight, 2) to maxOf(kotlin.math.round(safeHeight * safeRatio).toInt(), 2)
            } else {
                maxOf(kotlin.math.round(safeHeight / safeRatio).toInt(), 2) to maxOf(safeHeight, 2)
            }
        }

        /**
         * Sorts 4 quadrilateral points into [TL, TR, BR, BL] clockwise order,
         * and determines whether the quad is vertical (H < W).
         *
         * Exact port of Python's sort_pnts (utils/generic.py L324-353).
         */
        fun sortPoints(pts: List<PointF>): Pair<List<PointF>, Boolean> {
            if (pts.size != 4) return pts to false
            // Compute all 16 pairwise vectors (4 points × 4 points)
            val p = pts.toTypedArray()
            val pairwiseVectors = Array(16) { FloatArray(2) }
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    pairwiseVectors[i * 4 + j][0] = p[i].x - p[j].x
                    pairwiseVectors[i * 4 + j][1] = p[i].y - p[j].y
                }
            }
            // Find the 2 longest sides (indices 8 and 10 in sorted norms)
            val norms = pairwiseVectors.map { kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1]) }
            // np.argsort is ascending; match Python exactly
            val sortedNorms = norms.withIndex().sortedBy { it.value }
            val longSide1 = pairwiseVectors[sortedNorms[8].index]
            val longSide2 = pairwiseVectors[sortedNorms[10].index]
            // Align directions if inner product is negative
            val innerProd = longSide1[0] * longSide2[0] + longSide1[1] * longSide2[1]
            if (innerProd < 0) { longSide1[0] = -longSide1[0]; longSide1[1] = -longSide1[1] }
            val strucX = kotlin.math.abs(longSide1[0] + longSide2[0]) / 2f
            val strucY = kotlin.math.abs(longSide1[1] + longSide2[1]) / 2f
            val isVertical = strucX <= strucY

            // Sort: Python-style (matching sort_pnts)
            val sorted = pts.toMutableList()
            if (isVertical) {
                sorted.sortBy { it.y }  // sort by y
                // Top 2: sort by x ascending; bottom 2: sort by x descending
                val top = sorted.subList(0, 2).sortedBy { it.x }
                val bot = sorted.subList(2, 4).sortedByDescending { it.x }
                return (top + bot) to true     // [TL, TR, BR, BL]
            } else {
                sorted.sortBy { it.x }  // sort by x
                // Left 2: sort by y ascending; right 2: sort by y ascending
                val left = sorted.subList(0, 2).sortedBy { it.y }
                val right = sorted.subList(2, 4).sortedBy { it.y }
                val result = mutableListOf(left[0], right[0], right[1], left[1])
                return result to false  // [TL, TR, BR, BL]
            }
        }

        /** Old API — returns sorted points only. */
        fun sortPointsLegacy(pts: List<PointF>): List<PointF> = sortPoints(pts).first
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
