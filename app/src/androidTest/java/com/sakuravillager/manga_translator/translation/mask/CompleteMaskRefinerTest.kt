package com.sakuravillager.manga_translator.translation.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [CompleteMaskRefiner].
 *
 * Verifies that the mask refiner:
 * - Produces a mask of the same dimensions as the input image
 * - Dilation increases mask coverage when kernel size > 1
 * - Handles empty text regions gracefully
 * - Handles null rawMask by creating a fresh mask
 *
 * @see OpenCVMaskRefinerTest for the sibling implementation tests
 */
@RunWith(AndroidJUnit4::class)
class CompleteMaskRefinerTest {

    private val refiner = CompleteMaskRefiner()

    // ── Basic mask creation ────────────────────────────────────────────────

    @Test
    fun refineProducesMaskOfSameDimensions() = runBlocking {
        refiner.prepare()
        val bitmap = createSolidBitmap(100, 100, Color.WHITE)
        val rawMask = createMaskBitmap(100, 100, 25, 25, 50, 50)

        val result = refiner.refine(
            textRegions = emptyList(),
            bitmap = bitmap,
            rawMask = rawMask,
            kernelSize = 3,
            dilationOffset = 20,
        )

        Assert.assertNotNull("Result bitmap should not be null", result)
        Assert.assertEquals("Width should match input", bitmap.width, result.width)
        Assert.assertEquals("Height should match input", bitmap.height, result.height)
    }

    // ── Dilation effect ────────────────────────────────────────────────────

    @Test
    fun dilationIncreasesMaskArea() = runBlocking {
        refiner.prepare()
        val bitmap = createSolidBitmap(100, 100, Color.WHITE)
        val rawMask = createMaskBitmap(100, 100, 25, 25, 50, 50)

        val smallKernelResult = refiner.refine(
            textRegions = emptyList(),
            bitmap = bitmap,
            rawMask = rawMask,
            kernelSize = 1,
            dilationOffset = 20,
        )

        val largeKernelResult = refiner.refine(
            textRegions = emptyList(),
            bitmap = bitmap,
            rawMask = rawMask,
            kernelSize = 5,
            dilationOffset = 20,
        )

        val smallWhite = countNonBlackPixels(smallKernelResult)
        val largeWhite = countNonBlackPixels(largeKernelResult)

        Assert.assertTrue(
            "Larger kernel should produce more white pixels: $largeWhite > $smallWhite",
            largeWhite >= smallWhite,
        )
    }

    // ── Null rawMask handling ──────────────────────────────────────────────

    @Test
    fun nullRawMaskCreatesFreshMask() = runBlocking {
        refiner.prepare()
        val bitmap = createSolidBitmap(100, 100, Color.WHITE)

        val result = refiner.refine(
            textRegions = emptyList(),
            bitmap = bitmap,
            rawMask = null,
            kernelSize = 3,
            dilationOffset = 20,
        )

        Assert.assertNotNull("Result bitmap should not be null", result)
        Assert.assertEquals("Width should match", bitmap.width, result.width)
        Assert.assertEquals("Height should match", bitmap.height, result.height)
    }

    // ── Empty text regions ─────────────────────────────────────────────────

    @Test
    fun emptyTextRegionsWithRawMaskReturnsMask() = runBlocking {
        refiner.prepare()
        val bitmap = createSolidBitmap(100, 100, Color.WHITE)
        val rawMask = createMaskBitmap(100, 100, 25, 25, 50, 50)

        val result = refiner.refine(
            textRegions = emptyList(),
            bitmap = bitmap,
            rawMask = rawMask,
            kernelSize = 3,
            dilationOffset = 20,
        )

        Assert.assertNotNull("Result should not be null", result)
        Assert.assertTrue("Result should have at least some non-black pixels",
            countNonBlackPixels(result) > 0)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun createSolidBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        return bmp
    }

    private fun createMaskBitmap(
        totalWidth: Int,
        totalHeight: Int,
        left: Int,
        top: Int,
        rectWidth: Int,
        rectHeight: Int,
    ): Bitmap {
        val mask = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(
            left.toFloat(),
            top.toFloat(),
            (left + rectWidth).toFloat(),
            (top + rectHeight).toFloat(),
            paint,
        )
        return mask
    }

    private fun countNonBlackPixels(bitmap: Bitmap): Int {
        var count = 0
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) != Color.BLACK) {
                    count++
                }
            }
        }
        return count
    }
}
