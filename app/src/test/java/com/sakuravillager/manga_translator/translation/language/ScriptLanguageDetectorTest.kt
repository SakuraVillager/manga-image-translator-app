package com.sakuravillager.manga_translator.translation.language

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [ScriptLanguageDetector] covering core language detection and known edge cases.
 *
 * Known limitations (documented, not bugs):
 * - Pure CJK text (kanji only) cannot be distinguished from Chinese → returns CHS.
 * - Russian and Ukrainian both use Cyrillic → cannot distinguish.
 * - Very short text (<4 chars) always returns UNKNOWN.
 */
class ScriptLanguageDetectorTest {

    // ── Core language detection ────────────────────────────────────────────

    @Test
    fun `hiragana mixed with CJK detected as JPN`() {
        val result = ScriptLanguageDetector.detect("こんにちは世界")
        assertEquals("JPN", result.language)
        assertTrue("confidence should be > 0.5", result.confidence > 0.5f)
    }

    @Test
    fun `pure CJK detected as CHS`() {
        assertEquals("CHS", ScriptLanguageDetector.detect("你好世界").language)
    }

    @Test
    fun `CJK with Latin brackets detected as CHS`() {
        assertEquals("CHS", ScriptLanguageDetector.detect("「Hello」世界").language)
    }

    @Test
    fun `pure Latin detected as ENG`() {
        assertEquals("ENG", ScriptLanguageDetector.detect("Hello World").language)
    }

    @Test
    fun `very short text returns UNKNOWN`() {
        assertEquals("UNKNOWN", ScriptLanguageDetector.detect("A").language)
    }

    @Test
    fun `Korean detected as KOR`() {
        assertEquals("KOR", ScriptLanguageDetector.detect("안녕하세요").language)
    }

    @Test
    fun `Hiragana-only detected as JPN`() {
        assertEquals("JPN", ScriptLanguageDetector.detect("こんにちは").language)
    }

    @Test
    fun `Arabic text detected as ARA`() {
        assertEquals("ARA", ScriptLanguageDetector.detect("مرحبا بالعالم").language)
    }

    @Test
    fun `Thai text detected as THA`() {
        assertEquals("THA", ScriptLanguageDetector.detect("สวัสดีชาวโลก").language)
    }

    @Test
    fun `Cyrillic text detected as RUS`() {
        assertEquals("RUS", ScriptLanguageDetector.detect("Привет мир").language)
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `empty string returns UNKNOWN with zero confidence`() {
        val result = ScriptLanguageDetector.detect("")
        assertEquals("UNKNOWN", result.language)
        assertEquals(0f, result.confidence, 0.001f)
    }

    @Test
    fun `whitespace-only returns UNKNOWN`() {
        assertEquals("UNKNOWN", ScriptLanguageDetector.detect("    ").language)
    }

    @Test
    fun `pure digits returns UNKNOWN`() {
        assertEquals("UNKNOWN", ScriptLanguageDetector.detect("12345").language)
    }

    @Test
    fun `pure kanji returns CHS - known limitation`() {
        // Pure kanji text is ambiguous between JPN and CHS.
        // Heuristic cannot distinguish them without hiragana/katakana signals.
        // Documented as a known limitation.
        val result = ScriptLanguageDetector.detect("世界平和")
        assertEquals("CHS", result.language)
    }

    @Test
    fun `hiragana-only has high confidence`() {
        val result = ScriptLanguageDetector.detect("あいうえお")
        assertEquals("JPN", result.language)
        assertTrue("hiragana-only should have confidence > 0.8", result.confidence > 0.8f)
    }

    @Test
    fun `mixed CJK and Latin without hiragana returns CHS`() {
        val result = ScriptLanguageDetector.detect("你好Hello")
        assertEquals("CHS", result.language)
    }

    @Test
    fun `three chars returns UNKNOWN - below threshold`() {
        // 3 chars is below the 4-char minimum threshold
        assertEquals("UNKNOWN", ScriptLanguageDetector.detect("abc").language)
    }

    @Test
    fun `four chars is at threshold`() {
        val result = ScriptLanguageDetector.detect("abcd")
        assertEquals("ENG", result.language)
    }

    @Test
    fun `katakana mixed with CJK detected as JPN`() {
        // カタカナ + CJK, but no hiragana. The condition requires
        // hiraganaScore > 0 || (katakanaScore > 0 && hiragana+katakana >= cjkScore)
        val result = ScriptLanguageDetector.detect("カタカナ世界")
        assertEquals("JPN", result.language)
    }

    @Test
    fun `confidence is normalized between 0 and 1`() {
        val result = ScriptLanguageDetector.detect("こんにちは世界")
        assertTrue("confidence should be in [0, 1]", result.confidence in 0f..1f)
    }
}
