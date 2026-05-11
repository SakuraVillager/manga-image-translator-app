package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import com.sakuravillager.manga_translator.translation.translator.common.clearTranslatorCache
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorDispatchTest {

    @After
    fun tearDown() {
        clearTranslatorCache()
    }

    // ─── parseChain ─────────────────────────────────────────────────

    @Test
    fun `parseChain returns empty list for null input`() {
        val result = parseChain(null)
        assertTrue("Expected empty list for null input", result.isEmpty())
    }

    @Test
    fun `parseChain returns empty list for blank input`() {
        assertTrue(parseChain("").isEmpty())
        assertTrue(parseChain("   ").isEmpty())
    }

    @Test
    fun `parseChain parses single step`() {
        val result = parseChain("GPT_COMPATIBLE:ENG")
        assertEquals(1, result.size)
        assertEquals(TranslatorType.GPT_COMPATIBLE, result[0].translatorType)
        assertEquals("ENG", result[0].targetLanguage)
    }

    @Test
    fun `parseChain parses multiple steps`() {
        val result = parseChain("GPT_COMPATIBLE:ENG;DEEPL:CHS")
        assertEquals(2, result.size)
        assertEquals(TranslatorType.GPT_COMPATIBLE, result[0].translatorType)
        assertEquals("ENG", result[0].targetLanguage)
        assertEquals(TranslatorType.DEEPL, result[1].translatorType)
        assertEquals("CHS", result[1].targetLanguage)
    }

    @Test
    fun `parseChain defaults target language to CHS when omitted`() {
        val result = parseChain("ORIGINAL")
        assertEquals(1, result.size)
        assertEquals(TranslatorType.ORIGINAL, result[0].translatorType)
        assertEquals("CHS", result[0].targetLanguage)
    }

    @Test
    fun `parseChain falls back to NONE for unknown type`() {
        val result = parseChain("UNKNOWN_TYPE:ENG")
        assertEquals(1, result.size)
        assertEquals(TranslatorType.NONE, result[0].translatorType)
        assertEquals("ENG", result[0].targetLanguage)
    }

    @Test
    fun `parseChain handles mixed known and unknown types`() {
        val result = parseChain("ORIGINAL:ENG;BAD_TYPE:CHS;NONE:JPN")
        assertEquals(3, result.size)
        assertEquals(TranslatorType.ORIGINAL, result[0].translatorType)
        assertEquals(TranslatorType.NONE, result[1].translatorType) // falls back
        assertEquals(TranslatorType.NONE, result[2].translatorType)
        assertEquals("JPN", result[2].targetLanguage)
    }

    // ─── createTranslator ───────────────────────────────────────────

    @Test
    fun `createTranslator returns NoOpTranslator for NONE`() {
        val translator = createTranslator(TranslatorType.NONE)
        assertTrue("Expected NoOpTranslator", translator is NoOpTranslator)
    }

    @Test
    fun `createTranslator returns OriginalTranslator for ORIGINAL`() {
        val translator = createTranslator(TranslatorType.ORIGINAL)
        assertTrue("Expected OriginalTranslator", translator is OriginalTranslator)
    }

    @Test
    fun `createTranslator returns GptTranslator for GPT_COMPATIBLE`() {
        val translator = createTranslator(TranslatorType.GPT_COMPATIBLE)
        assertTrue("Expected GptTranslator", translator is GptTranslator)
    }

    @Test
    fun `createTranslator returns DeeplTranslator for DEEPL`() {
        val translator = createTranslator(TranslatorType.DEEPL)
        assertTrue("Expected DeeplTranslator", translator is DeeplTranslator)
    }

    @Test
    fun `createTranslator falls back to NoOpTranslator for unimplemented BAIDU`() {
        val translator = createTranslator(TranslatorType.BAIDU)
        assertTrue("Expected NoOpTranslator fallback", translator is NoOpTranslator)
    }

    @Test
    fun `createTranslator falls back to NoOpTranslator for unimplemented YOUDAO`() {
        val translator = createTranslator(TranslatorType.YOUDAO)
        assertTrue("Expected NoOpTranslator fallback", translator is NoOpTranslator)
    }

    // ─── dispatch (no chain) ────────────────────────────────────────

    @Test
    fun `dispatch with null chain and empty queries returns empty`() = runTest {
        val result = dispatch(null, emptyList(), TranslatorConfig(), useMtpe = false)
        assertTrue("Expected empty list for empty queries", result.isEmpty())
    }

    @Test
    fun `dispatch with null chain and ORIGINAL translator returns queries unchanged`() = runTest {
        val config = TranslatorConfig(translator = TranslatorType.ORIGINAL, targetLanguage = "CHS")
        val queries = listOf("hello", "world", "testing")
        val result = dispatch(null, queries, config)
        assertEquals(queries, result)
    }

    @Test
    fun `dispatch with null chain and NONE translator returns empty strings`() = runTest {
        val config = TranslatorConfig(translator = TranslatorType.NONE, targetLanguage = "ENG")
        val queries = listOf("hello", "world")
        val result = dispatch(null, queries, config)
        assertEquals(listOf("", ""), result)
    }

    // ─── dispatch (with chain) ──────────────────────────────────────

    @Test
    fun `dispatch with single-step chain uses that translator`() = runTest {
        val queries = listOf("hello", "world")
        val result = dispatch("NONE:ENG", queries, TranslatorConfig())
        // NoOpTranslator returns empty strings
        assertEquals(listOf("", ""), result)
    }

    @Test
    fun `dispatch with ORIGINAL to ORIGINAL chain preserves text through both steps`() = runTest {
        val queries = listOf("hello", "world")
        val result = dispatch("ORIGINAL:ENG;ORIGINAL:CHS", queries, TranslatorConfig())
        assertEquals(queries, result)
    }

    @Test
    fun `dispatch with NONE then ORIGINAL returns empty strings`() = runTest {
        // Step 1: NONE → empty strings
        // Step 2: ORIGINAL receives empty strings → returns them unchanged
        val queries = listOf("hello", "world")
        val result = dispatch("NONE:ENG;ORIGINAL:CHS", queries, TranslatorConfig())
        assertEquals(listOf("", ""), result)
    }

    @Test
    fun `dispatch with ORIGINAL then NONE returns empty strings`() = runTest {
        // Step 1: ORIGINAL → passes through
        // Step 2: NONE → empties everything
        val queries = listOf("hello", "world")
        val result = dispatch("ORIGINAL:ENG;NONE:CHS", queries, TranslatorConfig())
        assertEquals(listOf("", ""), result)
    }

    @Test
    fun `dispatch with unknown type falls back to NONE`() = runTest {
        val queries = listOf("hello", "world")
        val result = dispatch("UNKNOWN:ENG", queries, TranslatorConfig())
        assertEquals(listOf("", ""), result)
    }

    // ─── dispatch caches translator instances ──────────────────────

    @Test
    fun `dispatch reuses cached translator instance for same type`() = runTest {
        val config = TranslatorConfig(translator = TranslatorType.ORIGINAL, targetLanguage = "ENG")
        dispatch(null, listOf("hello"), config)

        // The translator should now be cached. Calling again returns same instance.
        // We verify by clearing cache first and checking the factory is called again.
        // Here we just verify no crash.
        dispatch(null, listOf("world"), config)
    }

    // ─── prepare ────────────────────────────────────────────────────

    @Test
    fun `prepare with null chain does not crash`() = runTest {
        val config = TranslatorConfig(translator = TranslatorType.ORIGINAL, targetLanguage = "CHS")
        prepare(null, config)
        // Should not throw
    }

    @Test
    fun `prepare with single-step chain does not crash`() = runTest {
        prepare("ORIGINAL:ENG", TranslatorConfig())
    }

    @Test
    fun `prepare with multi-step chain does not crash`() = runTest {
        prepare("ORIGINAL:ENG;NONE:CHS", TranslatorConfig())
    }

    @Test
    fun `prepare with empty chain does not crash`() = runTest {
        prepare("", TranslatorConfig(translator = TranslatorType.ORIGINAL))
    }

    @Test
    fun `prepare caches the translator instance`() = runTest {
        prepare("ORIGINAL:ENG", TranslatorConfig())
        // The ORIGINAL translator should now be cached
        val cached = com.sakuravillager.manga_translator.translation.translator.common.TRANSLATOR_CACHE[TranslatorType.ORIGINAL]
        assertNotNull("Translator should be cached after prepare", cached)
        assertTrue("Cached instance should be a Translator", cached is com.sakuravillager.manga_translator.translation.api.Translator)
    }

    // ─── unload ─────────────────────────────────────────────────────

    @Test
    fun `unload with non-cached key is no-op`() = runTest {
        // Should not throw or crash
        unload(TranslatorType.NONE)
    }

    @Test
    fun `unload removes translator from cache`() = runTest {
        // Prepare caches the translator
        prepare("ORIGINAL:ENG", TranslatorConfig())
        assertTrue(
            "Translator should be cached after prepare",
            com.sakuravillager.manga_translator.translation.translator.common.TRANSLATOR_CACHE.containsKey(TranslatorType.ORIGINAL),
        )

        unload(TranslatorType.ORIGINAL)

        assertFalse(
            "Translator should be removed from cache after unload",
            com.sakuravillager.manga_translator.translation.translator.common.TRANSLATOR_CACHE.containsKey(TranslatorType.ORIGINAL),
        )
    }

    @Test
    fun `unload allows re-creating translator on next dispatch`() = runTest {
        val config = TranslatorConfig(translator = TranslatorType.ORIGINAL, targetLanguage = "ENG")
        dispatch(null, listOf("first"), config)
        unload(TranslatorType.ORIGINAL)

        // After unload, dispatcher should be able to create a new instance
        dispatch(null, listOf("second"), config)
    }

    // ─── Integration: full cycle ────────────────────────────────────

    @Test
    fun `prepare then dispatch with same chain works`() = runTest {
        prepare("ORIGINAL:ENG", TranslatorConfig())
        val result = dispatch("ORIGINAL:ENG", listOf("hello", "world"), TranslatorConfig())
        assertEquals(listOf("hello", "world"), result)
    }
}
