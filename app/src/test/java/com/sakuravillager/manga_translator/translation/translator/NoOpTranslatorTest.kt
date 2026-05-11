package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpTranslatorTest {

    @Test
    fun `name is NoOpTranslator`() {
        val translator = NoOpTranslator()
        assertEquals("NoOpTranslator", translator.name)
    }

    @Test
    fun `translate returns empty strings for valuable text`() = runTest {
        val translator = NoOpTranslator()
        val result = translator.translate(
            listOf("hello", "world"),
            "ENG",
            "CHS",
            TranslatorConfig(),
        )
        assertEquals(listOf("", ""), result)
    }

    @Test
    fun `translate preserves non-valuable text`() = runTest {
        val translator = NoOpTranslator()
        val result = translator.translate(
            listOf("123", "!@#", "  "),
            "ENG",
            "CHS",
            TranslatorConfig(),
        )
        assertEquals(listOf("123", "!@#", "  "), result)
    }

    @Test
    fun `translate returns originals when from equals to`() = runTest {
        val translator = NoOpTranslator()
        val texts = listOf("hello", "world")
        val result = translator.translate(texts, "ENG", "ENG", TranslatorConfig())
        assertEquals(texts, result)
    }

    @Test
    fun `translate returns empty for empty input`() = runTest {
        val translator = NoOpTranslator()
        val result = translator.translate(emptyList(), "ENG", "CHS", TranslatorConfig())
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `translate handles mixed valuable and non-valuable text`() = runTest {
        val translator = NoOpTranslator()
        val result = translator.translate(
            listOf("hello", "123", "world", "..."),
            "ENG",
            "CHS",
            TranslatorConfig(),
        )
        // Non-valuable: "123", "..." → preserved; valuable: "hello", "world" → empty
        assertEquals(listOf("", "123", "", "..."), result)
    }
}
