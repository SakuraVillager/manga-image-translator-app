package com.sakuravillager.manga_translator.translation.data

import org.junit.Test
import org.junit.Assert.*

/**
 * JVM unit tests for [Quadrilateral], [TextDirection], and [TextAlignment].
 *
 * NOTE: Quadrilateral depends on Android classes (PointF, RectF, Bitmap),
 * so we only test non-Android properties here. Tests involving PointF/RectF/Bitmap
 * construction would require Robolectric or instrumented tests.
 */
class QuadrilateralTest {

    @Test
    fun defaultValuesAreCorrect() {
        val quad = Quadrilateral(points = emptyList())
        assertEquals("", quad.text)
        assertEquals(0f, quad.probability, 0f)
        assertEquals(TextDirection.AUTO, quad.direction)
        assertNull(quad.fgColor)
        assertNull(quad.bgColor)
    }

    @Test
    fun constructorAcceptsCustomValues() {
        val quad = Quadrilateral(
            points = emptyList(),
            text = "hello",
            probability = 0.95f,
            direction = TextDirection.VERTICAL,
            fgColor = 0xFF0000,
            bgColor = 0xFFFFFF
        )
        assertEquals("hello", quad.text)
        assertEquals(0.95f, quad.probability, 0.001f)
        assertEquals(TextDirection.VERTICAL, quad.direction)
        assertEquals(0xFF0000.toInt(), quad.fgColor)
        assertEquals(0xFFFFFF.toInt(), quad.bgColor)
    }

    @Test
    fun emptyPointsListIsAccepted() {
        val quad = Quadrilateral(points = emptyList())
        assertTrue(quad.points.isEmpty())
        assertEquals(0, quad.points.size)
    }

    @Test
    fun textDirectionEnumHasExactlyFourValues() {
        val values = TextDirection.values()
        assertEquals(4, values.size)
    }

    @Test
    fun textDirectionEnumContainsAllExpectedValues() {
        assertTrue(TextDirection.values().contains(TextDirection.AUTO))
        assertTrue(TextDirection.values().contains(TextDirection.HORIZONTAL))
        assertTrue(TextDirection.values().contains(TextDirection.VERTICAL))
        assertTrue(TextDirection.values().contains(TextDirection.HORIZONTAL_RTL))
    }

    @Test
    fun textAlignmentEnumHasExactlyFourValues() {
        val values = TextAlignment.values()
        assertEquals(4, values.size)
    }

    @Test
    fun textAlignmentEnumContainsAllExpectedValues() {
        assertTrue(TextAlignment.values().contains(TextAlignment.AUTO))
        assertTrue(TextAlignment.values().contains(TextAlignment.LEFT))
        assertTrue(TextAlignment.values().contains(TextAlignment.CENTER))
        assertTrue(TextAlignment.values().contains(TextAlignment.RIGHT))
    }

    @Test
    fun textDirectionAUTOIsDefault() {
        assertEquals(TextDirection.AUTO, TextDirection.valueOf("AUTO"))
    }

    @Test
    fun textAlignmentAUTOIsDefault() {
        assertEquals(TextAlignment.AUTO, TextAlignment.valueOf("AUTO"))
    }
}
