package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OriginalTranslatorTest {

    @Test
    fun `name is OriginalTranslator`() {
        val translator = OriginalTranslator()
        assertEquals("OriginalTranslator", translator.name)
    }

    @Test
    fun `translate returns queries as-is`() = runTest {
        val translator = OriginalTranslator()
        val texts = listOf("hello", "world", "こんにちは")
        val result = translator.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(texts, result)
    }

    @Test
    fun `translate preserves non-valuable text`() = runTest {
        val translator = OriginalTranslator()
        val texts = listOf("123", "!@#", "  ")
        val result = translator.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(texts, result)
    }

    @Test
    fun `translate returns originals when from equals to`() = runTest {
        val translator = OriginalTranslator()
        val texts = listOf("hello", "world")
        val result = translator.translate(texts, "ENG", "ENG", TranslatorConfig())
        assertEquals(texts, result)
    }

    @Test
    fun `translate returns empty for empty input`() = runTest {
        val translator = OriginalTranslator()
        val result = translator.translate(emptyList(), "ENG", "CHS", TranslatorConfig())
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `translate handles mixed valuable and non-valuable text`() = runTest {
        val translator = OriginalTranslator()
        val texts = listOf("hello", "123", "world", "...")
        val result = translator.translate(texts, "ENG", "CHS", TranslatorConfig())
        assertEquals(texts, result)
    }
}
