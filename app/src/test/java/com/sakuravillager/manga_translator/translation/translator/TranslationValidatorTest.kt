package com.sakuravillager.manga_translator.translation.translator

import org.junit.Test
import org.junit.Assert.*

class TranslationValidatorTest {

    // ── hasRepetition ──────────────────────────────────────────

    @Test
    fun `hasRepetition detects long repeated sequence`() {
        assertTrue(TranslationValidator.hasRepetition("AAAAA", threshold = 5))
    }

    @Test
    fun `hasRepetition returns false for normal text`() {
        assertFalse(TranslationValidator.hasRepetition("Hello World", threshold = 20))
    }

    @Test
    fun `hasRepetition returns false for short text`() {
        assertFalse(TranslationValidator.hasRepetition("AA", threshold = 20))
    }

    // ── isTargetLanguageRatio ──────────────────────────────────

    @Test
    fun `isTargetLanguageRatio returns true for CHS text`() {
        assertTrue(TranslationValidator.isTargetLanguageRatio("你好世界", "CHS"))
    }

    @Test
    fun `isTargetLanguageRatio returns false for English in CHS mode`() {
        assertFalse(TranslationValidator.isTargetLanguageRatio("Hello World", "CHS"))
    }

    @Test
    fun `isTargetLanguageRatio returns true for JPN text`() {
        assertTrue(TranslationValidator.isTargetLanguageRatio("こんにちは世界", "JPN"))
    }

    // ── validate ───────────────────────────────────────────────

    @Test
    fun `validate passes for good translation`() {
        assertTrue(TranslationValidator.validate("こんにちは", "Hello", "ENG"))
    }

    @Test
    fun `validate fails for unchanged text`() {
        assertFalse(TranslationValidator.validate("こんにちは", "こんにちは", "ENG"))
    }

    @Test
    fun `validate fails for empty translation`() {
        assertFalse(TranslationValidator.validate("hello", "", "CHS"))
    }

    @Test
    fun `validate fails for repetitive hallucination`() {
        assertFalse(TranslationValidator.validate("hello", "A".repeat(30), "CHS"))
    }
}
