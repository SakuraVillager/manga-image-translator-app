package com.sakuravillager.manga_translator.translation.translator.common

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTranslatorTest {

    // ─── Mock subclass ──────────────────────────────────────────────

    /**
     * A concrete [OfflineTranslator] that records calls to [_infer], [_load],
     * and [_unload] for verification.
     */
    private class MockOfflineTranslator(
        override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
            "ENG" to "en",
            "CHS" to "zh",
            "JPN" to "ja",
        ),
        private val inferResult: (List<String>) -> List<String> = { it },
    ) : OfflineTranslator() {

        val inferCalls = mutableListOf<Triple<String, String, List<String>>>()
        val loadCalls = mutableListOf<Triple<String, String, String>>()
        var unloadCalls = 0

        override suspend fun _infer(
            fromLang: String,
            toLang: String,
            queries: List<String>,
        ): List<String> {
            inferCalls.add(Triple(fromLang, toLang, queries))
            return inferResult(queries)
        }

        override suspend fun _load(fromLang: String, toLang: String, device: String) {
            loadCalls.add(Triple(fromLang, toLang, device))
        }

        override suspend fun _unload() {
            unloadCalls++
        }

        // ── Expose protected methods for testing ──

        suspend fun call_translate(
            fromLang: String,
            toLang: String,
            queries: List<String>,
        ): List<String> = _translate(fromLang, toLang, queries)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _MODEL_SUB_DIR
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun modelSubDir_equals_translators() {
        assertEquals("translators", OfflineTranslator._MODEL_SUB_DIR)
    }

    // ═══════════════════════════════════════════════════════════════
    //  _translate delegates to _infer
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun translate_forwardsArgumentsToInfer() = runTest {
        val t = MockOfflineTranslator()
        val result = t.call_translate("en", "zh", listOf("hello", "world"))
        assertEquals(listOf("hello", "world"), result)
        assertEquals(1, t.inferCalls.size)
        val (from, to, queries) = t.inferCalls[0]
        assertEquals("en", from)
        assertEquals("zh", to)
        assertEquals(listOf("hello", "world"), queries)
    }

    @Test
    fun translate_returnsInferResult() = runTest {
        val t = MockOfflineTranslator(
            inferResult = { queries -> queries.map { "TR($it)" } },
        )
        val result = t.call_translate("en", "zh", listOf("hello"))
        assertEquals(listOf("TR(hello)"), result)
    }

    // ═══════════════════════════════════════════════════════════════
    //  load
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun load_resolvesLanguageCodesAndCallsLoad() = runTest {
        val t = MockOfflineTranslator()
        t.load("ENG", "CHS", "cpu")

        assertEquals(1, t.loadCalls.size)
        val (from, to, device) = t.loadCalls[0]
        assertEquals("en", from)   // resolved via _LANGUAGE_CODE_MAP
        assertEquals("zh", to)     // resolved via _LANGUAGE_CODE_MAP
        assertEquals("cpu", device)
    }

    @Test
    fun load_passesAutoThrough() = runTest {
        val t = MockOfflineTranslator()
        t.load("auto", "ENG", "cuda")

        assertEquals(1, t.loadCalls.size)
        val (from, to, device) = t.loadCalls[0]
        assertEquals("auto", from)
        assertEquals("en", to)
        assertEquals("cuda", device)
    }

    @Test
    fun load_throws_forUnsupportedLanguage() = runTest {
        val t = MockOfflineTranslator()
        try {
            t.load("FRA", "ENG", "cpu")
            // Should not reach here
            assertTrue("Expected exception was not thrown", false)
        } catch (_: LanguageUnsupportedException) {
            // Expected
        }
        assertEquals(0, t.loadCalls.size) // _load should NOT have been called
    }

    // ═══════════════════════════════════════════════════════════════
    //  unload
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun unload_callsUnload() = runTest {
        val t = MockOfflineTranslator()
        assertEquals(0, t.unloadCalls)
        t.unload()
        assertEquals(1, t.unloadCalls)
    }

    @Test
    fun unload_canBeCalledMultipleTimes() = runTest {
        val t = MockOfflineTranslator()
        t.unload()
        t.unload()
        t.unload()
        assertEquals(3, t.unloadCalls)
    }

    // ═══════════════════════════════════════════════════════════════
    //  reload
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun reload_callsUnloadThenLoad() = runTest {
        val t = MockOfflineTranslator()
        assertEquals(0, t.unloadCalls)
        assertEquals(0, t.loadCalls.size)

        t.reload("ENG", "CHS", "cpu")

        // unload should be called first, then load
        assertEquals(1, t.unloadCalls)
        assertEquals(1, t.loadCalls.size)
        val (from, to, device) = t.loadCalls[0]
        assertEquals("en", from)
        assertEquals("zh", to)
        assertEquals("cpu", device)
    }

    @Test
    fun reload_throws_forUnsupportedLanguage() = runTest {
        val t = MockOfflineTranslator()
        try {
            t.reload("FRA", "ENG", "cpu")
            assertTrue("Expected exception was not thrown", false)
        } catch (_: LanguageUnsupportedException) {
            // Expected
        }
        // unload should still have been called before load failed
        assertEquals(1, t.unloadCalls)
        assertEquals(0, t.loadCalls.size)
    }
}
