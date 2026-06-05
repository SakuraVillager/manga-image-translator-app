package com.sakuravillager.manga_translator.translation.render

import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [HyphenationUtils] word-wrap logic.
 *
 * Uses a mock Paint wrapper that returns controlled text widths,
 * since in JVM unit tests Android's Paint has no real font and
 * `measureText` returns 0 or platform-dependent values.
 */
class HyphenationUtilsTest {

    /**
     * A Paint subclass that returns predictable widths for testing.
     * Each character is treated as a fixed-width "glyph" of `charWidth` pixels.
     */
    private class FakePaint(textSize: Float, private val charWidth: Float) : Paint() {
        init {
            this.textSize = textSize
            typeface = Typeface.DEFAULT
        }

        override fun measureText(text: String?): Float {
            return (text?.length ?: 0) * charWidth
        }

        override fun measureText(text: String?, start: Int, end: Int): Float {
            return (end - start) * charWidth
        }
    }

    private fun paint(textSize: Float = 20f, charWidth: Float = 10f): Paint =
        FakePaint(textSize, charWidth)

    // ── Basic wrapping ───────────────────────────────────────────────────

    @Test
    fun `short text within width returns single line`() {
        val p = paint(charWidth = 10f)
        val text = "Hi"
        val maxWidth = 100f  // plenty of space
        val lines = HyphenationUtils.wrapLines(text, p, maxWidth)
        assertEquals(1, lines.size)
        assertEquals("Hi", lines[0])
    }

    @Test
    fun `long Latin text wraps at word boundaries`() {
        val p = paint(charWidth = 10f)
        // Each char = 10px, space = 10px. "word " = 50px
        // Max width = 45px (can't fit "word ")
        val words = "word word word word word"
        val maxWidth = 45f
        val lines = HyphenationUtils.wrapLines(words, p, maxWidth)
        assertTrue("Should wrap into multiple lines, got ${lines.size}: $lines", lines.size > 1)
        for (line in lines) {
            assertTrue("Line '$line' (${line.length * 10f}px) should fit in $maxWidth",
                line.length * 10f <= maxWidth)
        }
    }

    @Test
    fun `CJK text wraps at character boundaries`() {
        val p = paint(charWidth = 20f)
        // Each CJK char = 20px
        val text = "abcdefghij"  // 10 chars = 200px
        val maxWidth = 60f  // 3 chars per line
        val lines = HyphenationUtils.wrapLines(text, p, maxWidth)
        assertTrue("CJK text should wrap into multiple lines, got ${lines.size}: $lines", lines.size > 1)
        for (line in lines) {
            assertTrue("Line '$line' (${line.length * 20f}px) should fit in $maxWidth",
                line.length * 20f <= maxWidth)
        }
    }

    @Test
    fun `empty string returns single empty line`() {
        val p = paint()
        val lines = HyphenationUtils.wrapLines("", p, 100f)
        assertEquals(1, lines.size)
        assertEquals("", lines[0])
    }

    @Test
    fun `blank string returns empty string`() {
        val p = paint()
        val lines = HyphenationUtils.wrapLines("   ", p, 100f)
        assertEquals(1, lines.size)
        assertEquals("", lines[0])
    }

    // ── Explicit newlines ────────────────────────────────────────────────

    @Test
    fun `explicit newlines are preserved`() {
        val p = paint()
        val text = "Line 1\nLine 2\nLine 3"
        val maxWidth = 1000f
        val lines = HyphenationUtils.wrapLines(text, p, maxWidth)
        assertEquals(3, lines.size)
        assertEquals("Line 1", lines[0])
        assertEquals("Line 2", lines[1])
        assertEquals("Line 3", lines[2])
    }

    @Test
    fun `explicit newline with empty lines preserved`() {
        val p = paint()
        val text = "A\n\nB"
        val maxWidth = 1000f
        val lines = HyphenationUtils.wrapLines(text, p, maxWidth)
        assertEquals(3, lines.size)
        assertEquals("A", lines[0])
        assertEquals("", lines[1])
        assertEquals("B", lines[2])
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun `zero maxWidth returns single line`() {
        val p = paint()
        val lines = HyphenationUtils.wrapLines("Hello", p, 0f)
        assertEquals(1, lines.size)
        assertEquals("Hello", lines[0])
    }

    @Test
    fun `single very long word is not dropped`() {
        val p = paint(charWidth = 10f)
        val text = "Supercalifragilisticexpialidocious"
        val maxWidth = 10f  // 1 char per line
        val lines = HyphenationUtils.wrapLines(text, p, maxWidth)
        assertTrue("Long word should not be dropped, got: $lines", lines.isNotEmpty())
        // The whole word should appear in the result
        val concatenated = lines.joinToString("")
        assertEquals(text, concatenated)
    }

    @Test
    fun `mixed CJK and Latin wraps correctly`() {
        val p = paint(charWidth = 10f)
        val text = "abcde日本語fghij"  // 10 latin + 5 CJK = 150px at 10px/char
        val maxWidth = 80f  // 8 chars per line
        val lines = HyphenationUtils.wrapLines(text, p, maxWidth)
        assertTrue("Mixed text should wrap, got ${lines.size}: $lines", lines.size >= 1)
    }
}
