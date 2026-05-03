package com.sakuravillager.manga_translator.translation.inpaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SimpleFillInpainterTest {

    private lateinit var inpainter: SimpleFillInpainter
    private val config = InpainterConfig()

    @Before
    fun setUp() {
        inpainter = SimpleFillInpainter()
    }

    @Test
    fun inpaint_maskWhiteArea_becomesWhite() = runBlocking {
        inpainter.prepare()
        assertTrue(inpainter.isReady)

        // Create 100x100 blue bitmap
        val bitmap = createSolidBitmap(100, 100, Color.BLUE)

        // Create 100x100 mask with top-left 50x50 white, rest black
        val mask = createMaskBitmap(100, 100, 0, 0, 50, 50)

        val result = inpainter.inpaint(bitmap, mask, config)

        // Pixel in masked area should be white
        assertTrue(result.getPixel(10, 10) == Color.WHITE, "Pixel inside mask should be white")

        // Pixel outside masked area should remain blue
        assertTrue(result.getPixel(60, 60) == Color.BLUE, "Pixel outside mask should remain blue")
    }

    @Test
    fun inpaint_transparentMask_returnsOriginal() = runBlocking {
        inpainter.prepare()
        assertTrue(inpainter.isReady)

        val bitmap = createSolidBitmap(100, 100, Color.BLUE)

        // All-black mask — fully transparent (no inpainting)
        val mask = createSolidBitmap(100, 100, Color.BLACK)

        val result = inpainter.inpaint(bitmap, mask, config)

        // Result should match original
        assertTrue(result.sameAs(bitmap), "All-black mask should leave bitmap unchanged")
    }

    @Test
    fun inpaint_fullCoverageMask_returnsAllWhite() = runBlocking {
        inpainter.prepare()
        assertTrue(inpainter.isReady)

        val bitmap = createSolidBitmap(100, 100, Color.BLUE)

        // All-white mask — full inpainting coverage
        val mask = createSolidBitmap(100, 100, Color.WHITE)

        val result = inpainter.inpaint(bitmap, mask, config)

        // All pixels should be white
        for (x in 0 until result.width step 10) {
            for (y in 0 until result.height step 10) {
                assertTrue(
                    result.getPixel(x, y) == Color.WHITE,
                    "Pixel at ($x, $y) should be white with full-coverage mask",
                )
            }
        }
    }

    @Test
    fun inpaint_mismatchedDimensions_resizesMask() = runBlocking {
        inpainter.prepare()
        assertTrue(inpainter.isReady)

        val bitmap = createSolidBitmap(100, 100, Color.BLUE)

        // 50x50 all-white mask - should be resized to 100x100
        val mask = createSolidBitmap(50, 50, Color.WHITE)

        val result = inpainter.inpaint(bitmap, mask, config)

        // After resize the mask covers everything, so all pixels should be white
        assertTrue(
            result.getPixel(0, 0) == Color.WHITE,
            "Pixel should be white after mask resize",
        )
        assertTrue(
            result.getPixel(99, 99) == Color.WHITE,
            "Pixel should be white after mask resize",
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a solid-color ARGB_8888 bitmap of the given size. */
    private fun createSolidBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        return bmp
    }

    /**
     * Creates a mask bitmap with white pixels in [left, top, left+width, top+height]
     * and black everywhere else.
     */
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
        // Fill with black
        canvas.drawColor(Color.BLACK)
        // Draw white rectangle
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
}
