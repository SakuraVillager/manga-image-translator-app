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

@RunWith(AndroidJUnit4::class)
class OpenCVMaskRefinerTest {

    private val refiner = OpenCVMaskRefiner()

    @Test
    fun testDilationIncreasesWhitePixels() = runBlocking {
        refiner.prepare()

        // Create 100x100 white input bitmap
        val bitmap = createSolidBitmap(100, 100, Color.WHITE)

        // Create rawMask with 50x50 white rect at (25,25,75,75)
        val rawMask = createMaskBitmap(100, 100, 25, 25, 50, 50)

        val rawWhiteCount = countNonBlackPixels(rawMask)

        val result = refiner.refine(
            textRegions = emptyList(),
            bitmap = bitmap,
            rawMask = rawMask,
            kernelSize = 5,
            dilationOffset = 20,
        )

        val resultWhiteCount = countNonBlackPixels(result)

        Assert.assertTrue(
            "White pixels should increase after dilation: $resultWhiteCount > $rawWhiteCount",
            resultWhiteCount > rawWhiteCount,
        )
    }

    @Test
    fun testNullRawMaskCreatesFreshMask() = runBlocking {
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

    @Test
    fun testEmptyTextRegionsReturnsZeroMask() = runBlocking {
        refiner.prepare()

        val bitmap = createSolidBitmap(100, 100, Color.WHITE)

        val result = refiner.refine(
            textRegions = emptyList(),
            bitmap = bitmap,
            rawMask = null,
            kernelSize = 3,
            dilationOffset = 20,
        )

        val whiteCount = countNonBlackPixels(result)
        Assert.assertEquals("All-zero mask should have no white pixels", 0, whiteCount)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
