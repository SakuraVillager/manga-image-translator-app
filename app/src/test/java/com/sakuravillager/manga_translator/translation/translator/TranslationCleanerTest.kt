package com.sakuravillager.manga_translator.translation.translator

import org.junit.Test
import org.junit.Assert.*

class TranslationCleanerTest {

    @Test
    fun `cleanTranslation collapses multiple whitespace`() {
        assertEquals("Hello World", TranslationValidator.cleanTranslation("Hello   World"))
    }

    @Test
    fun `cleanTranslation adds space after punctuation before word`() {
        assertEquals("text. text", TranslationValidator.cleanTranslation("text.text"))
    }

    @Test
    fun `cleanTranslation removes space between consecutive punctuation`() {
        assertEquals("!!..", TranslationValidator.cleanTranslation("! ! . ."))
    }

    @Test
    fun `cleanTranslation removes space before trailing punctuation`() {
        assertEquals("text.", TranslationValidator.cleanTranslation("text ."))
    }

    @Test
    fun `cleanTranslation handles repeating sequence with query`() {
        // Python's logic: shrink only when translation is shorter than query
        // "aaa" (3 chars) < "abcdef" (6 chars) → shrink to match query length
        assertEquals("aaaaaa", TranslationValidator.cleanTranslation("aaa", "abcdef"))
    }

    @Test
    fun `cleanTranslation does not shrink when translation is not shorter than query`() {
        // "aaaaaa" (6 chars) is NOT < "abc" (3 chars) → no shrinking
        assertEquals("aaaaaa", TranslationValidator.cleanTranslation("aaaaaa", "abc"))
    }

    @Test
    fun `cleanTranslation handles empty string`() {
        assertEquals("", TranslationValidator.cleanTranslation(""))
    }

    @Test
    fun `cleanTranslation handles Chinese text`() {
        assertEquals("你好。 世界", TranslationValidator.cleanTranslation("你好。  世界"))
    }
}
