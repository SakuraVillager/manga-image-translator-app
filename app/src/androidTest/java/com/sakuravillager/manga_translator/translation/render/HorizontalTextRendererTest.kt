package com.sakuravillager.manga_translator.translation.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class HorizontalTextRendererTest {

    private lateinit var renderer: HorizontalTextRenderer
    private val config = RendererConfig()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dummyMd = com.sakuravillager.manga_translator.translation.model.ModelDownloadManager(context)
        renderer = HorizontalTextRenderer(context, dummyMd)
    }

    @Test
    fun render_drawsText_atTextBlockPosition() = runBlocking {
        renderer.prepare()
        assertTrue(renderer.isReady)

        val width = 200
        val height = 200
        val original = createSolidBitmap(width, height, Color.WHITE)

        val textBlock = TextBlock(
            lines = listOf(
                listOf(
                    PointF(10f, 10f),
                    PointF(180f, 10f),
                    PointF(180f, 40f),
                    PointF(10f, 40f),
                ),
            ),
            translation = "Test",
            fontSize = 20f,
        )

        val textRegions = listOf(textBlock)
        val result = renderer.render(original, textRegions, config)

        // Verify dimensions unchanged
        assertTrue(result.width == width, "Width should be unchanged")
        assertTrue(result.height == height, "Height should be unchanged")

        // Verify pixel at (50, 30) — inside the text bounding rect — has changed
        // (text was drawn at this position)
        assertNotEquals(
            original.getPixel(50, 30),
            result.getPixel(50, 30),
            "Pixel inside text region should differ after rendering text",
        )

        // Verify result is not the same object as original (defensive copy)
        assertTrue(result !== original, "Renderer should return a new bitmap copy")
    }

    @Test
    fun render_emptyTextRegions_returnsOriginal() = runBlocking {
        renderer.prepare()
        assertTrue(renderer.isReady)

        val original = createSolidBitmap(100, 100, Color.WHITE)
        val result = renderer.render(original, emptyList(), config)

        assertTrue(result.sameAs(original), "Empty regions should return bitmap unchanged")
    }

    @Test
    fun render_emptyTranslation_skipsRegion() = runBlocking {
        renderer.prepare()
        assertTrue(renderer.isReady)

        val original = createSolidBitmap(100, 100, Color.WHITE)

        val textBlock = TextBlock(
            lines = listOf(
                listOf(
                    PointF(10f, 10f),
                    PointF(90f, 10f),
                    PointF(90f, 40f),
                    PointF(10f, 40f),
                ),
            ),
            translation = "",
            fontSize = 20f,
        )

        val textRegions = listOf(textBlock)
        val result = renderer.render(original, textRegions, config)

        assertTrue(result.sameAs(original), "Empty translation should leave bitmap unchanged")
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
}
