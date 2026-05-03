package com.sakuravillager.manga_translator.translation.ocr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [OcrDictionary.decodeTokenIds].
 *
 * Since OcrDictionary loads from Android assets (requires instrumented tests),
 * we use reflection to inject a test dictionary into the private `_chars` field.
 * This allows us to test the decodeTokenIds logic in pure JVM unit tests.
 */
class OcrDictionaryTest {

    /** Test dictionary: indices 0-4 are special tokens, 5 is space, 6+ are chars. */
    private val testChars = listOf(
        "<PAD>",   // 0
        "<S>",     // 1
        "</S>",    // 2
        "<SEP>",   // 3
        "<UNK>",   // 4
        "<SP>",    // 5
        "a",       // 6
        "b",       // 7
        "c",       // 8
        "d",       // 9
        "啊",      // 10 — CJK character
        "好",      // 11
    )

    @Before
    fun setUp() {
        injectTestChars(testChars)
    }

    @After
    fun tearDown() {
        // Reset _chars to null so other tests don't see our injected data
        injectTestChars(null)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun injectTestChars(chars: List<String>?) {
        val field = OcrDictionary::class.java.getDeclaredField("_chars")
        field.isAccessible = true
        field.set(OcrDictionary, chars)
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    fun `decodeTokenIds strips start and end tokens`() {
        // <S> a b c </S> → "abc"
        val result = OcrDictionary.decodeTokenIds(intArrayOf(1, 6, 7, 8, 2))
        assertEquals("abc", result)
    }

    @Test
    fun `decodeTokenIds emits space for SPACE token`() {
        // a <SP> b → "a b"
        val result = OcrDictionary.decodeTokenIds(intArrayOf(6, 5, 7))
        assertEquals("a b", result)
    }

    @Test
    fun `decodeTokenIds skips special tokens`() {
        // <PAD> <S> a <SEP> b <UNK> c </S> → "abc"
        val result = OcrDictionary.decodeTokenIds(intArrayOf(0, 1, 6, 3, 7, 4, 8, 2))
        assertEquals("abc", result)
    }

    @Test
    fun `decodeTokenIds stops at END token`() {
        // a b </S> c d → "ab"
        val result = OcrDictionary.decodeTokenIds(intArrayOf(6, 7, 2, 8, 9))
        assertEquals("ab", result)
    }

    @Test
    fun `decodeTokenIds handles CJK characters`() {
        // 啊 好 → "啊好"
        val result = OcrDictionary.decodeTokenIds(intArrayOf(10, 11))
        assertEquals("啊好", result)
    }

    @Test
    fun `decodeTokenIds handles empty array`() {
        val result = OcrDictionary.decodeTokenIds(intArrayOf())
        assertEquals("", result)
    }

    @Test
    fun `decodeTokenIds handles only special tokens`() {
        // <PAD> <S> <SEP> <UNK> → ""
        val result = OcrDictionary.decodeTokenIds(intArrayOf(0, 1, 3, 4))
        assertEquals("", result)
    }

    @Test
    fun `decodeTokenIds handles array starting with END`() {
        // </S> a b → ""
        val result = OcrDictionary.decodeTokenIds(intArrayOf(2, 6, 7))
        assertEquals("", result)
    }

    @Test
    fun `decodeTokenIds handles consecutive spaces`() {
        // a <SP> <SP> b → "a  b"
        val result = OcrDictionary.decodeTokenIds(intArrayOf(6, 5, 5, 7))
        assertEquals("a  b", result)
    }

    @Test
    fun `decodeTokenIds without loading throws error`() {
        tearDown() // ensure _chars is null
        val exception = assertThrows(IllegalStateException::class.java) {
            OcrDictionary.decodeTokenIds(intArrayOf(6))
        }
        assertEquals("OcrDictionary not loaded. Call load(context) first.", exception.message)
    }

    @Test
    fun `decodeTokenIds handles out-of-range index gracefully`() {
        // Index 99 is beyond our test dictionary of size 12
        // It won't match any case in the when-expression — should be skipped
        val result = OcrDictionary.decodeTokenIds(intArrayOf(6, 99, 7))
        assertEquals("ab", result)
    }

    @Test
    fun `chars property returns injected dictionary`() {
        val result = OcrDictionary.chars
        assertEquals(testChars, result)
    }

    @Test
    fun `size property reflects dictionary size`() {
        assertEquals(testChars.size, OcrDictionary.size)
    }
}
