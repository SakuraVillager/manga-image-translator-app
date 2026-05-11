package com.sakuravillager.manga_translator.translation.translator

import org.junit.Test
import org.junit.Assert.*

class TranslationValidatorSymbolRatioTest {

    // ── hasLowUniqueSymbolRatio ─────────────────────────────────

    @Test
    fun `hasLowUniqueSymbolRatio returns false for normal Chinese text`() {
        assertFalse(TranslationValidator.hasLowUniqueSymbolRatio("这是一段正常的翻译文本", 7))
    }

    @Test
    fun `hasLowUniqueSymbolRatio returns true for repetitive symbols`() {
        assertTrue(TranslationValidator.hasLowUniqueSymbolRatio("！！！！！！！！！", 7))
    }

    @Test
    fun `hasLowUniqueSymbolRatio returns false for empty string`() {
        assertFalse(TranslationValidator.hasLowUniqueSymbolRatio("", 5))
    }

    @Test
    fun `hasLowUniqueSymbolRatio returns false for short text less than 6`() {
        assertFalse(TranslationValidator.hasLowUniqueSymbolRatio("ab", 5))
    }

    @Test
    fun `hasLowUniqueSymbolRatio returns true for text with few valuable symbols`() {
        assertTrue(TranslationValidator.hasLowUniqueSymbolRatio("a!!!!!", 5))
    }

    // ── validate integration ─────────────────────────────────────

    @Test
    fun `validate fails for low unique symbol ratio translation`() {
        assertFalse(TranslationValidator.validate("原文内容在这里", "！！！！！！！！！", "CHS"))
    }
}
