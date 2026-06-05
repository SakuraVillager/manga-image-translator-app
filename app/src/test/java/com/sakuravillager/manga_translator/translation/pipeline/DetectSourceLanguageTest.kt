package com.sakuravillager.manga_translator.translation.pipeline

import com.sakuravillager.manga_translator.translation.language.ScriptLanguageDetector
import org.junit.Test
import org.junit.Assert.*

class DetectSourceLanguageTest {

    @Test
    fun `hiragana mixed with CJK detected as JPN`() {
        assertEquals("JPN", ScriptLanguageDetector.detect("こんにちは世界").language)
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
}
