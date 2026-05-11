package com.sakuravillager.manga_translator.translation.pipeline

import org.junit.Test
import org.junit.Assert.*

class DetectSourceLanguageTest {

    @Test
    fun `hiragana mixed with CJK detected as JPN`() {
        assertEquals("JPN", detectSourceLanguage("こんにちは世界").first)
    }

    @Test
    fun `pure CJK detected as CHS`() {
        assertEquals("CHS", detectSourceLanguage("你好世界").first)
    }

    @Test
    fun `CJK with Latin brackets detected as CHS`() {
        assertEquals("CHS", detectSourceLanguage("「Hello」世界").first)
    }

    @Test
    fun `pure Latin detected as ENG`() {
        assertEquals("ENG", detectSourceLanguage("Hello World").first)
    }

    @Test
    fun `very short text returns UNKNOWN`() {
        assertEquals("UNKNOWN", detectSourceLanguage("A").first)
    }

    @Test
    fun `Korean detected as KOR`() {
        assertEquals("KOR", detectSourceLanguage("안녕하세요").first)
    }

    @Test
    fun `Hiragana-only detected as JPN`() {
        assertEquals("JPN", detectSourceLanguage("こんにちは").first)
    }

    @Test
    fun `Arabic text detected as ARA`() {
        assertEquals("ARA", detectSourceLanguage("مرحبا بالعالم").first)
    }

    @Test
    fun `Thai text detected as THA`() {
        assertEquals("THA", detectSourceLanguage("สวัสดีชาวโลก").first)
    }

    @Test
    fun `Cyrillic text detected as RUS`() {
        assertEquals("RUS", detectSourceLanguage("Привет мир").first)
    }
}
