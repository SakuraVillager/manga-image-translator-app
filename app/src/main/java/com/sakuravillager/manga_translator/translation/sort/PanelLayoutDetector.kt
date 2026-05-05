package com.sakuravillager.manga_translator.translation.sort

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

object PanelLayoutDetector {
    fun detectPanels(bitmap: Bitmap): List<RectF> {
        val src = Mat()
        val gray = Mat()
        val binary = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Utils.bitmapToMat(bitmap, src)
            if (src.empty()) return emptyList()

            if (src.channels() == 4) {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
            }

            // Dark content becomes foreground; white gutters remain background.
            Imgproc.threshold(gray, binary, 245.0, 255.0, Imgproc.THRESH_BINARY_INV)

            val kernelSize = max(3, min(gray.cols(), gray.rows()) / 120)
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelSize.toDouble(), kernelSize.toDouble()),
            )
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(binary, binary, kernel)

            Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val pageArea = bitmap.width.toFloat() * bitmap.height.toFloat()
            val minArea = pageArea * 0.01f
            val rects = contours.mapNotNull { contour ->
                val rect = Imgproc.boundingRect(MatOfPoint2f(*contour.toArray()))
                val area = rect.width.toFloat() * rect.height.toFloat()
                if (area < minArea) null else RectF(
                    rect.x.toFloat(),
                    rect.y.toFloat(),
                    (rect.x + rect.width).toFloat(),
                    (rect.y + rect.height).toFloat(),
                )
            }

            return mergeRects(rects, bitmap.width, bitmap.height)
        } finally {
            for (contour in contours) contour.release()
            hierarchy.release()
            binary.release()
            gray.release()
            src.release()
        }
    }

    private fun mergeRects(
        rects: List<RectF>,
        pageWidth: Int,
        pageHeight: Int,
    ): List<RectF> {
        if (rects.isEmpty()) return emptyList()

        val merged = rects.toMutableList()
        val gapThreshold = max(24f, min(pageWidth, pageHeight) * 0.02f)

        var changed: Boolean
        do {
            changed = false
            outer@ for (i in 0 until merged.size) {
                for (j in i + 1 until merged.size) {
                    val a = merged[i]
                    val b = merged[j]
                    if (shouldMerge(a, b, gapThreshold)) {
                        merged[i] = union(a, b)
                        merged.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        } while (changed)

        return merged.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })
    }

    private fun shouldMerge(a: RectF, b: RectF, gapThreshold: Float): Boolean {
        if (RectF.intersects(a, b)) return true

        val horizontalGap = when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0f
        }
        val verticalOverlap = min(a.bottom, b.bottom) - max(a.top, b.top)

        val verticalGap = when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0f
        }
        val horizontalOverlap = min(a.right, b.right) - max(a.left, b.left)

        return (horizontalOverlap > 0f && verticalGap <= gapThreshold) ||
            (verticalOverlap > 0f && horizontalGap <= gapThreshold)
    }

    private fun union(a: RectF, b: RectF): RectF {
        return RectF(
            min(a.left, b.left),
            min(a.top, b.top),
            max(a.right, b.right),
            max(a.bottom, b.bottom),
        )
    }
}