package com.sakuravillager.manga_translator.translation.translator.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExceptionsTest {

    @Test
    fun `InvalidServerResponse is a RuntimeException with message`() {
        val ex = InvalidServerResponse("Server returned 500")
        assertTrue(ex is RuntimeException)
        assertEquals("Server returned 500", ex.message)
    }

    @Test
    fun `MissingAPIKeyException is a RuntimeException with message`() {
        val ex = MissingAPIKeyException("API key not configured")
        assertTrue(ex is RuntimeException)
        assertEquals("API key not configured", ex.message)
    }

    @Test
    fun `LanguageUnsupportedException uses default translator name when null`() {
        val ex = LanguageUnsupportedException(languageCode = "xx")
        assertEquals("Language not supported for chosen translator: \"xx\"", ex.message)
    }

    @Test
    fun `LanguageUnsupportedException includes translator name when provided`() {
        val ex = LanguageUnsupportedException(languageCode = "xx", translator = "DeepL")
        assertEquals("Language not supported for DeepL: \"xx\"", ex.message)
    }

    @Test
    fun `LanguageUnsupportedException appends supported languages when provided`() {
        val ex = LanguageUnsupportedException(
            languageCode = "xx",
            translator = "DeepL",
            supportedLanguages = listOf("en", "ja", "zh"),
        )
        assertEquals(
            "Language not supported for DeepL: \"xx\". Supported languages: \"en,ja,zh\"",
            ex.message,
        )
    }

    @Test
    fun `LanguageUnsupportedException with only supported languages`() {
        val ex = LanguageUnsupportedException(
            languageCode = "xx",
            supportedLanguages = listOf("en", "ja"),
        )
        assertEquals(
            "Language not supported for chosen translator: \"xx\". Supported languages: \"en,ja\"",
            ex.message,
        )
    }

    @Test
    fun `LanguageUnsupportedException with empty supported languages list`() {
        val ex = LanguageUnsupportedException(
            languageCode = "xx",
            translator = "GPT",
            supportedLanguages = emptyList(),
        )
        assertEquals("Language not supported for GPT: \"xx\"", ex.message)
    }
}
