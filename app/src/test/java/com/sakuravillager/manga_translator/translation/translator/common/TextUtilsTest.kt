package com.sakuravillager.manga_translator.translation.translator.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TextUtilsTest {

    // ── isPunctuation ──────────────────────────────────────────

    @Test
    fun `isPunctuation returns true for ASCII punctuation`() {
        assertTrue(TextUtils.isPunctuation('!'))
        assertTrue(TextUtils.isPunctuation('.'))
        assertTrue(TextUtils.isPunctuation(','))
        assertTrue(TextUtils.isPunctuation('?'))
        assertTrue(TextUtils.isPunctuation(';'))
        assertTrue(TextUtils.isPunctuation('('))
        assertTrue(TextUtils.isPunctuation('"'))
    }

    @Test
    fun `isPunctuation returns true for Unicode punctuation`() {
        assertTrue(TextUtils.isPunctuation('¿'))
        assertTrue(TextUtils.isPunctuation('「'))
        assertTrue(TextUtils.isPunctuation('」'))
        assertTrue(TextUtils.isPunctuation('—'))
    }

    @Test
    fun `isPunctuation returns false for letters and digits`() {
        assertFalse(TextUtils.isPunctuation('a'))
        assertFalse(TextUtils.isPunctuation('Z'))
        assertFalse(TextUtils.isPunctuation('5'))
        assertFalse(TextUtils.isPunctuation('中'))
    }

    // ── isValuableChar ─────────────────────────────────────────

    @Test
    fun `isValuableChar returns true for ASCII letter`() {
        assertTrue(TextUtils.isValuableChar('a'))
        assertTrue(TextUtils.isValuableChar('Z'))
    }

    @Test
    fun `isValuableChar returns false for digits`() {
        assertFalse(TextUtils.isValuableChar('0'))
        assertFalse(TextUtils.isValuableChar('5'))
        assertFalse(TextUtils.isValuableChar('9'))
    }

    @Test
    fun `isValuableChar returns false for whitespace`() {
        assertFalse(TextUtils.isValuableChar(' '))
        assertFalse(TextUtils.isValuableChar('\t'))
        assertFalse(TextUtils.isValuableChar('\n'))
        assertFalse(TextUtils.isValuableChar('\r'))
    }

    @Test
    fun `isValuableChar returns false for punctuation`() {
        assertFalse(TextUtils.isValuableChar('!'))
        assertFalse(TextUtils.isValuableChar('.'))
        assertFalse(TextUtils.isValuableChar(','))
        assertFalse(TextUtils.isValuableChar('?'))
    }

    @Test
    fun `isValuableChar returns false for control characters`() {
        assertFalse(TextUtils.isValuableChar('\u0000'))
        assertFalse(TextUtils.isValuableChar('\u0003'))
    }

    @Test
    fun `isValuableChar returns true for CJK characters`() {
        assertTrue(TextUtils.isValuableChar('中'))
        assertTrue(TextUtils.isValuableChar('国'))
        assertTrue(TextUtils.isValuableChar('あ'))
        assertTrue(TextUtils.isValuableChar('ア'))
        assertTrue(TextUtils.isValuableChar('한'))
    }

    @Test
    fun `isValuableChar returns false for surrogate chars`() {
        // Supplementary code points (e.g., emoji 😀 = U+1F600) are encoded as surrogate pairs.
        // Individual surrogate code units are not valuable characters.
        val highSurrogate = '\uD83D'
        val lowSurrogate = '\uDE00'
        assertFalse(TextUtils.isValuableChar(highSurrogate))
        assertFalse(TextUtils.isValuableChar(lowSurrogate))
    }

    // ── countValuableText ──────────────────────────────────────

    @Test
    fun `countValuableText counts only valuable characters`() {
        assertEquals(3, TextUtils.countValuableText("abc123"))     // a, b, c = 3
        assertEquals(0, TextUtils.countValuableText("   "))        // 0
        assertEquals(0, TextUtils.countValuableText("!@#"))        // 0
        assertEquals(7, TextUtils.countValuableText("Hello 世界"))  // H,e,l,l,o,世,界 = 7
    }

    @Test
    fun `countValuableText returns 0 for empty string`() {
        assertEquals(0, TextUtils.countValuableText(""))
    }

    @Test
    fun `countValuableText returns 0 for string with only digits`() {
        assertEquals(0, TextUtils.countValuableText("123456"))
    }

    @Test
    fun `countValuableText returns 0 for string with only punctuation`() {
        assertEquals(0, TextUtils.countValuableText("...,!?;"))
    }

    // ── isValuableText ─────────────────────────────────────────

    @Test
    fun `isValuableText returns true when text has valuable chars`() {
        assertTrue(TextUtils.isValuableText("abc"))
        assertTrue(TextUtils.isValuableText("Hello 世界"))
        assertTrue(TextUtils.isValuableText("a1b2c3"))
    }

    @Test
    fun `isValuableText returns false when all chars are not valuable`() {
        assertFalse(TextUtils.isValuableText("123"))
        assertFalse(TextUtils.isValuableText("   "))
        assertFalse(TextUtils.isValuableText("!@#"))
        assertFalse(TextUtils.isValuableText("\t\n\r"))
    }

    @Test
    fun `isValuableText returns false for empty string`() {
        assertFalse(TextUtils.isValuableText(""))
    }
}
