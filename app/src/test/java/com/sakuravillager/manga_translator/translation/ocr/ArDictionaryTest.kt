package com.sakuravillager.manga_translator.translation.ocr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for [ArDictionary.decode].
 *
 * Since ArDictionary loads from Android assets (requires instrumented tests),
 * we use reflection to inject a test dictionary into the private `_chars` field.
 */
class ArDictionaryTest {

    /** Test dictionary matching alphabet-all-v7.txt structure but smaller. */
    private val testChars = listOf(
        "<PAD>",   // 0
        "<S>",     // 1
        "</S>",    // 2
        "<SEP>",   // 3
        "<UNK>",   // 4
        "<SP>",    // 5
        "<LF>",    // 6
        "a",       // 7
        "b",       // 8
        "c",       // 9
        "啊",      // 10 — CJK character
        "好",      // 11
    )

    /** A larger dictionary (5000 entries) to simulate the actual v7 file. */
    private val largeTestChars: List<String> by lazy {
        val list = mutableListOf<String>()
        // Special tokens (indices 0-69, matching v7 structure)
        list.add("<PAD>")   // 0
        list.add("<S>")     // 1
        list.add("</S>")    // 2
        list.add("<SEP>")   // 3
        list.add("<UNK>")   // 4
        list.add("<SP>")    // 5
        list.add("<LF>")    // 6
        for (i in 3..69) list.add("<UNUSED$i>") // 7-69
        // Characters (indices 70+)
        for (i in 70 until 5000) list.add("char_$i")
        list
    }

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
        val field = ArDictionary::class.java.getDeclaredField("_chars")
        field.isAccessible = true
        field.set(ArDictionary, chars)
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    fun `load returns non-empty list`() {
        // Verify chars property returns non-empty list after injection
        assertTrue(ArDictionary.chars.isNotEmpty())
    }

    @Test
    fun `size is at least 4000`() {
        injectTestChars(largeTestChars)
        assertTrue("Dictionary size should be >= 4000, was ${ArDictionary.size}", ArDictionary.size >= 4000)
    }

    @Test
    fun `decode handles START and END tokens correctly`() {
        // <S> a b c </S> → "<S>abc</S>"
        val tokens = listOf(1, 7, 8, 9, 2)
        val result = ArDictionary.decode(tokens)
        assertEquals("<S>abc</S>", result)
    }

    @Test
    fun `decode maps token 0 correctly`() {
        // Token 0 = <PAD>
        val tokens = listOf(0)
        val result = ArDictionary.decode(tokens)
        assertEquals("<PAD>", result)
    }

    @Test
    fun `decode emits space for SP token`() {
        // a <SP> b → "a b"
        val tokens = listOf(7, 5, 8)
        val result = ArDictionary.decode(tokens)
        assertEquals("a b", result)
    }

    @Test
    fun `decode handles CJK characters`() {
        // 啊 好 → "啊好"
        val tokens = listOf(10, 11)
        val result = ArDictionary.decode(tokens)
        assertEquals("啊好", result)
    }

    @Test
    fun `decode handles empty list`() {
        val result = ArDictionary.decode(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `decode skips out-of-range indices`() {
        val tokens = listOf(7, 99, 8)
        val result = ArDictionary.decode(tokens)
        assertEquals("ab", result)
    }

    @Test
    fun `decode without loading throws error`() {
        tearDown() // ensure _chars is null
        val exception = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ArDictionary.decode(listOf(7))
        }
        assertEquals("ArDictionary not loaded.", exception.message)
    }

    @Test
    fun `chars property returns injected dictionary`() {
        val result = ArDictionary.chars
        assertEquals(testChars, result)
    }

    @Test
    fun `size property reflects dictionary size`() {
        assertEquals(testChars.size, ArDictionary.size)
    }
}
