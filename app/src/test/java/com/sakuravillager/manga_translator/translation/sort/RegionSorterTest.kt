package com.sakuravillager.manga_translator.translation.sort

import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.TextDirection
import com.sakuravillager.manga_translator.translation.pt
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [RegionSorter].
 *
 * Verifies sorting logic for text regions, including:
 * - Empty list handling
 * - Single region passthrough
 * - Top-to-bottom primary sort
 * - Right-to-left (RTL) vs left-to-right (LTR) secondary sort
 * - `forceSimpleSort` fallback
 *
 * Note: TextBlock equality is reference-based for List fields, so we compare
 * center positions rather than object identity.
 */
class RegionSorterTest {

    private fun textBlockAt(x: Float, y: Float): TextBlock = TextBlock(
        lines = listOf(listOf(pt(x, y), pt(x + 20f, y), pt(x + 20f, y + 10f), pt(x, y + 10f))),
    )

    private fun TextBlock.xy() = center.x to center.y

    // ── Basic edge cases ─────────────────────────────────────────────────

    @Test
    fun `empty list returns empty list`() {
        val result = RegionSorter.sortRegions(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single region returns same region`() {
        val region = textBlockAt(10f, 20f)
        val result = RegionSorter.sortRegions(listOf(region))
        assertEquals(1, result.size)
        assertEquals(region.xy(), result[0].xy())
    }

    // ── Top-to-bottom sort (Y primary) ───────────────────────────────────

    @Test
    fun `two regions different Y are sorted top to bottom`() {
        val top = textBlockAt(10f, 10f)
        val bottom = textBlockAt(10f, 100f)
        val result = RegionSorter.sortRegions(listOf(bottom, top))
        assertEquals(top.xy(), result[0].xy())
        assertEquals(bottom.xy(), result[1].xy())
    }

    // ── Right-to-left (RTL) sort (X secondary) ───────────────────────────

    @Test
    fun `two regions same Y with RTL sorted right to left`() {
        val left = textBlockAt(10f, 50f)
        val right = textBlockAt(200f, 50f)
        val result = RegionSorter.sortRegions(listOf(left, right), rightToLeft = true)
        // RTL: higher x first
        assertEquals(right.xy(), result[0].xy())
        assertEquals(left.xy(), result[1].xy())
    }

    @Test
    fun `two regions same Y with LTR sorted left to right`() {
        val left = textBlockAt(10f, 50f)
        val right = textBlockAt(200f, 50f)
        val result = RegionSorter.sortRegions(listOf(right, left), rightToLeft = false)
        // LTR: lower x first
        assertEquals(left.xy(), result[0].xy())
        assertEquals(right.xy(), result[1].xy())
    }

    // ── Force simple sort ───────────────────────────────────────────────

    @Test
    fun `forceSimpleSort true takes simple path`() {
        val topLeft = textBlockAt(10f, 10f)
        val topRight = textBlockAt(200f, 10f)
        val bottom = textBlockAt(100f, 100f)
        val result = RegionSorter.sortRegions(
            listOf(bottom, topRight, topLeft),
            rightToLeft = true,
            forceSimpleSort = true,
        )
        // simpleSort sorts by Y first, then by X within same row
        // rightToLeft=true means higher X comes first within the same row
        assertEquals(topRight.xy(), result[0].xy())
        assertEquals(topLeft.xy(), result[1].xy())
        assertEquals(bottom.xy(), result[2].xy())
    }

    // ── Grouping within rows ─────────────────────────────────────────────

    @Test
    fun `Y-spread greater than X-spread sorts by Y first`() {
        val top = textBlockAt(50f, 10f)
        val bottom = textBlockAt(50f, 200f)
        val result = RegionSorter.sortRegions(listOf(bottom, top))
        assertEquals(top.xy(), result[0].xy())
        assertEquals(bottom.xy(), result[1].xy())
    }

    // ── Direction field passthrough ──────────────────────────────────────

    @Test
    fun `direction field does not affect sorting`() {
        val horizontal = TextBlock(
            lines = listOf(listOf(pt(0f, 0f), pt(40f, 0f), pt(40f, 10f), pt(0f, 10f))),
            _direction = TextDirection.HORIZONTAL,
        )
        val vertical = TextBlock(
            lines = listOf(listOf(pt(0f, 100f), pt(10f, 100f), pt(10f, 140f), pt(0f, 140f))),
            _direction = TextDirection.VERTICAL,
        )
        val result = RegionSorter.sortRegions(listOf(vertical, horizontal))
        // Y-based sort: horizontal at y=5 vs vertical at y=120
        assertEquals(horizontal.xy(), result[0].xy())
        assertEquals(vertical.xy(), result[1].xy())
    }
}
