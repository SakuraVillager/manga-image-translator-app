package com.sakuravillager.manga_translator.translation.ocr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [OcrDictionary.ctcDecodeToText].
 *
 * Since OcrDictionary loads from Android assets (requires instrumented tests),
 * we use reflection to inject a test dictionary into the private `_chars` field.
 * This allows us to test the CTC decode logic in pure JVM unit tests.
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
        val decoded = listOf(6 to 0f, 7 to 0f, 8 to 0f) // skip <S>(1) and </S>(2)
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("abc", result)
    }

    @Test
    fun `decodeTokenIds emits space for SPACE token`() {
        // a <SP> b → "a b"
        val decoded = listOf(6 to 0f, 5 to 0f, 7 to 0f)
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("a b", result)
    }

    @Test
    fun `decodeTokenIds skips special tokens`() {
        // <PAD> <S> a <SEP> b <UNK> c </S> → "abc"
        val decoded = listOf(6 to 0f, 7 to 0f, 8 to 0f) // skip 0,1,3,4,2
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("abc", result)
    }

    @Test
    fun `decodeTokenIds stops at END token`() {
        // a b </S> c d → "ab"
        val decoded = listOf(6 to 0f, 7 to 0f) // stop before END(2)
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("ab", result)
    }

    @Test
    fun `decodeTokenIds handles CJK characters`() {
        // 啊 好 → "啊好"
        val decoded = listOf(10 to 0f, 11 to 0f)
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("啊好", result)
    }

    @Test
    fun `decodeTokenIds handles empty array`() {
        val result = OcrDictionary.ctcDecodeToText(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `decodeTokenIds handles only special tokens`() {
        // Python-style decode preserves non-SPACE special tokens.
        val decoded = listOf(0 to 0f, 1 to 0f, 3 to 0f, 4 to 0f)
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("<PAD><S><SEP><UNK>", result)
    }

    @Test
    fun `decodeTokenIds handles array starting with END`() {
        // </S> a b → ""
        val decoded = emptyList<Pair<Int, Float>>() // stop before END(2) at position 0
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("", result)
    }

    @Test
    fun `decodeTokenIds handles consecutive spaces`() {
        // a <SP> <SP> b → "a  b"
        val decoded = listOf(6 to 0f, 5 to 0f, 5 to 0f, 7 to 0f)
        val result = OcrDictionary.ctcDecodeToText(decoded)
        assertEquals("a  b", result)
    }

    @Test
    fun `decodeTokenIds without loading throws error`() {
        tearDown() // ensure _chars is null
        val exception = assertThrows(IllegalStateException::class.java) {
            OcrDictionary.ctcDecodeToText(listOf(6 to 0f))
        }
        assertEquals("OcrDictionary not loaded.", exception.message)
    }

    @Test
    fun `decodeTokenIds handles out-of-range index gracefully`() {
        // Index 99 is beyond our test dictionary of size 12
        // It won't match any case in the when-expression — should be skipped
        val decoded = listOf(6 to 0f, 99 to 0f, 7 to 0f)
        val result = OcrDictionary.ctcDecodeToText(decoded)
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
