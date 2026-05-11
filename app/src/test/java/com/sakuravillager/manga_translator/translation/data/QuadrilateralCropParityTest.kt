package com.sakuravillager.manga_translator.translation.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QuadrilateralCropParityTest {

    @Test
    fun `horizontal region size follows python-style width expansion`() {
        val (width, height) = Quadrilateral.transformedRegionSize(
            ratio = 0.25f,
            direction = TextDirection.HORIZONTAL,
            textHeight = 48,
        )

        assertEquals(192, width)
        assertEquals(48, height)
    }

    @Test
    fun `vertical region size keeps text height on width axis`() {
        val (width, height) = Quadrilateral.transformedRegionSize(
            ratio = 0.25f,
            direction = TextDirection.VERTICAL,
            textHeight = 48,
        )

        assertEquals(48, width)
        assertEquals(12, height)
    }

    @Test
    fun `transformed region size clamps invalid height and ratio`() {
        val (width, height) = Quadrilateral.transformedRegionSize(
            ratio = 0f,
            direction = TextDirection.HORIZONTAL,
            textHeight = 0,
        )

        assertEquals(10000, width)
        assertEquals(2, height)
    }
}
