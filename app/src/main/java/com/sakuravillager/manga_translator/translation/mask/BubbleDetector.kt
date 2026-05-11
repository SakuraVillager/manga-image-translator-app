package com.sakuravillager.manga_translator.translation.mask

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.util.bitmapToMat
import com.sakuravillager.manga_translator.translation.util.ensureOpenCVLoaded
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * Utility object for detecting whether a text region lies inside a speech bubble.
 *
 * Speech bubbles in manga have a distinctive visual structure:
 * - A clearly defined boundary (high edge density from the bubble outline).
 * - A mostly uniform interior (low pixel variance from the solid white/black fill).
 *
 * Regions outside speech bubbles tend to show:
 * - Lower edge density (no enclosing boundary).
 * - Higher pixel variance (detailed background art, textures, or gradients).
 *
 * Usage:
 * ```kotlin
 * val shouldIgnore = BubbleDetector.isIgnore(textBlock, pageBitmap)
 * if (shouldIgnore) skipRegion()
 * else processRegion()
 * ```
 */
object BubbleDetector {

    private const val TAG = "BubbleDetector"

    /**
     * Determines whether [region] should be ignored (i.e., is NOT inside a
     * speech bubble).
     *
     * The decision is based on two metrics extracted from the sub-region of
     * [bitmap] bounded by [region.minRect]:
     *  1. **Edge density** — fraction of edge pixels after Canny detection.
     *     Low edge density suggests no enclosing bubble boundary.
     *  2. **Pixel variance** — standard deviation of the grayscale values.
     *     High variance suggests detailed background rather than a uniform
     *     bubble fill.
     *
     * Returns `true` (ignore) when edge density falls below [threshold]‰
     * (per‑thousand) **or** variance exceeds an empirical threshold.
     * Returns `false` (keep) when the region looks like a speech bubble.
     *
     * @param region   detected text block to evaluate.
     * @param bitmap   full page bitmap from which the region is cropped.
     * @param threshold edge-density threshold in ‰ (per‑thousand). Default 5
     *                  means 0.5 % edge pixels → likely not a bubble.
     * @return `true` if the region should be ignored, `false` otherwise.
     */
    fun isIgnore(
        region: TextBlock,
        bitmap: Bitmap,
        threshold: Int = 5,
    ): Boolean {
        val rect = region.minRect
        val x = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val y = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val w = (rect.width().toInt()).coerceIn(1, bitmap.width - x)
        val h = (rect.height().toInt()).coerceIn(1, bitmap.height - y)

        // Crop the region of interest from the full bitmap
        val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)

        return try {
            if (!ensureOpenCVLoaded()) return false

            analyzeRegion(cropped, threshold)
        } finally {
            cropped.recycle()
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Core analysis: converts [cropped] to grayscale, computes edge density
     * and pixel variance, then decides whether the region looks like a bubble.
     */
    private fun analyzeRegion(cropped: Bitmap, threshold: Int): Boolean {
        var srcMat: Mat? = null
        var gray: Mat? = null
        var edges: Mat? = null
        var mean: MatOfDouble? = null
        var stddev: MatOfDouble? = null

        return try {
            srcMat = bitmapToMat(cropped)

            // Convert to grayscale
            gray = Mat()
            Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGBA2GRAY)

            // ── Edge density via Canny ──────────────────────────────────
            edges = Mat()
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            val edgeCount = Core.countNonZero(edges)
            val totalPixels = gray.rows().toLong() * gray.cols().toLong()
            val edgeDensity = if (totalPixels > 0) {
                (edgeCount.toDouble() / totalPixels.toDouble()) * 1000.0  // ‰
            } else {
                0.0
            }

            // ── Pixel variance ─────────────────────────────────────────
            mean = MatOfDouble()
            stddev = MatOfDouble()
            Core.meanStdDev(gray, mean, stddev)
            val variance = stddev.get(0, 0)[0]

            // ── Decision logic ─────────────────────────────────────────
            // Low edge density + high variance → not a speech bubble.
            val edgeThreshold = threshold.toDouble()  // ‰
            val varianceThreshold = 60.0               // grayscale stddev

            edgeDensity < edgeThreshold || variance > varianceThreshold
        } finally {
            srcMat?.release()
            gray?.release()
            edges?.release()
            mean?.release()
            stddev?.release()
        }
    }
}
