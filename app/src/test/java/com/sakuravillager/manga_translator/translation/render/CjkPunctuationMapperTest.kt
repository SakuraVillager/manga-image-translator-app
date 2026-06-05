package com.sakuravillager.manga_translator.translation.render

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [CjkPunctuationMapper] CJK punctuation mapping.
 *
 * Ported from Python `text_render.py` `CJK_H2V` and `CJK_V2H` dicts.
 * These tests verify the mapping behavior for well-known character pairs.
 * Some edge cases (duplicate keys in Python dict, exact round-trip) may not
 * hold due to the different data structure semantics.
 */
class CjkPunctuationMapperTest {

    // ── Map size and structure ───────────────────────────────────────────

    @Test
    fun `H2V map has expected entry count`() {
        // Python CJK_H2V has ~90 entries
        assertTrue("H2V map should have at least 80 entries", CjkPunctuationMapper.horizontalToVertical.size >= 80)
    }

    @Test
    fun `all H2V entries are non-empty`() {
        // Every entry maps to a non-blank string
        for ((_, v) in CjkPunctuationMapper.horizontalToVertical) {
            assertFalse("H2V value should not be blank", v.isBlank())
        }
    }

    // ── Character-level translation ──────────────────────────────────────

    @Test
    fun `translateForDirection converts horizontal parentheses to vertical`() {
        assertEquals("︵", CjkPunctuationMapper.translateForDirection("(", 1))
        assertEquals("︶", CjkPunctuationMapper.translateForDirection(")", 1))
    }

    @Test
    fun `translateForDirection converts vertical brackets to horizontal`() {
        // Use characters with unique values in horizontalToVertical (no duplicate targets)
        // ！ (U+FF01) maps to vertical ︕ (U+FF55) — unambiguous both ways
        assertEquals("!", CjkPunctuationMapper.translateForDirection("︕", 0))
        // 。 maps to vertical ︒ — unambiguous
        assertEquals(".", CjkPunctuationMapper.translateForDirection("︒", 0))
        // ， maps to vertical ︐ — unambiguous
        assertEquals(",", CjkPunctuationMapper.translateForDirection("︐", 0))
    }

    @Test
    fun `CJK opening quotes map to vertical form`() {
        // 「 (U+300C) maps to ﹁ (U+FF41)
        assertEquals("﹁", CjkPunctuationMapper.translateForDirection("「", 1))
        assertEquals("﹂", CjkPunctuationMapper.translateForDirection("」", 1))
    }

    @Test
    fun `non-mapped characters pass through unchanged in either direction`() {
        // Regular Latin characters are not in CJK maps
        assertEquals("A", CjkPunctuationMapper.translateForDirection("A", 0))
        assertEquals("A", CjkPunctuationMapper.translateForDirection("A", 1))
        // CJK characters that aren't punctuation pass through
        assertEquals("日", CjkPunctuationMapper.translateForDirection("日", 0))
        assertEquals("日", CjkPunctuationMapper.translateForDirection("日", 1))
    }

    @Test
    fun `translateForDirection with invalid direction returns char unchanged`() {
        assertEquals("。", CjkPunctuationMapper.translateForDirection("。", 2))
        assertEquals("。", CjkPunctuationMapper.translateForDirection("。", -1))
    }

    // ── String-level translation ─────────────────────────────────────────

    @Test
    fun `translateForVertical handles empty string`() {
        assertEquals("", CjkPunctuationMapper.translateForVertical(""))
    }

    @Test
    fun `translateForHorizontal handles empty string`() {
        assertEquals("", CjkPunctuationMapper.translateForHorizontal(""))
    }

    @Test
    fun `translateForVertical maps multiple punctuation marks`() {
        val result = CjkPunctuationMapper.translateForVertical("「テスト」")
        // The quote marks should be converted
        assertTrue("Should contain vertical opening quote: $result", result.contains("﹁"))
        assertTrue("Should contain vertical closing quote: $result", result.contains("﹂"))
    }

    // ── Known punctuation mappings ───────────────────────────────────────

    @Test
    fun `Japanese period maps to vertical period`() {
        assertEquals("︒", CjkPunctuationMapper.translateForDirection("。", 1))
    }

    @Test
    fun `Japanese comma maps to vertical comma`() {
        assertEquals("︐", CjkPunctuationMapper.translateForDirection("，", 1))
    }

    @Test
    fun `horizontal dash maps to vertical dash`() {
        assertEquals("︲", CjkPunctuationMapper.translateForDirection("-", 1))
    }

    @Test
    fun `horizontal ellipsis maps to vertical dots`() {
        assertEquals("⋮", CjkPunctuationMapper.translateForDirection("…", 1))
    }
}
