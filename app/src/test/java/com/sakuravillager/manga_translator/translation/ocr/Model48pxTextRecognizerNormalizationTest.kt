package com.sakuravillager.manga_translator.translation.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class Model48pxTextRecognizerNormalizationTest {

    @Test
    fun `normalizePixel maps black to negative one`() {
        val (r, g, b) = Model48pxTextRecognizer.normalizePixel(0xFF000000.toInt())
        assertEquals(-1f, r, 0.0001f)
        assertEquals(-1f, g, 0.0001f)
        assertEquals(-1f, b, 0.0001f)
    }

    @Test
    fun `normalizePixel maps white to positive one`() {
        val (r, g, b) = Model48pxTextRecognizer.normalizePixel(0xFFFFFFFF.toInt())
        assertEquals(1f, r, 0.0001f)
        assertEquals(1f, g, 0.0001f)
        assertEquals(1f, b, 0.0001f)
    }

    @Test
    fun `normalizePixel maps mid gray near zero`() {
        val (r, g, b) = Model48pxTextRecognizer.normalizePixel(0xFF808080.toInt())
        assertEquals(0.0039216f, r, 0.0001f)
        assertEquals(0.0039216f, g, 0.0001f)
        assertEquals(0.0039216f, b, 0.0001f)
    }
}
