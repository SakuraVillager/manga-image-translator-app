package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import org.junit.Test
import org.junit.Assert.*
import android.app.Application

/**
 * JVM unit tests for [SugoiTranslator], [JparacrawlTranslator], and
 * [JparacrawlBigTranslator].
 *
 * Tests the language code maps which are pure Kotlin with no ONNX Runtime
 * dependency.
 */
class SugoiTranslatorTest {

    /**
     * Helper subclass that exposes the protected [_LANGUAGE_CODE_MAP].
     */
    private class ExposedSugoiTranslator(
        downloadManager: ModelDownloadManager,
        sessionManager: OnnxSessionManager,
    ) : SugoiTranslator(downloadManager, sessionManager) {
        val exposedLanguageCodeMap: Map<String, String>
            get() = _LANGUAGE_CODE_MAP
    }

    /**
     * Helper subclass exposing [_LANGUAGE_CODE_MAP] for JParaCrawl.
     */
    private class ExposedJparacrawlTranslator(
        downloadManager: ModelDownloadManager,
        sessionManager: OnnxSessionManager,
    ) : JparacrawlTranslator(downloadManager, sessionManager) {
        val exposedLanguageCodeMap: Map<String, String>
            get() = _LANGUAGE_CODE_MAP
    }

    // ─── Sugoi language code map ─────────────────────────────────────

    @Test
    fun sugoiLanguageCodeMapContainsExpectedMappings() {
        val ctx = Application()
        val translator = ExposedSugoiTranslator(ModelDownloadManager(ctx), OnnxSessionManager)
        val map = translator.exposedLanguageCodeMap

        // Sugoi V4 supports JPN ↔ ENG + CHS → ENG
        assertEquals("ja", map["JPN"])
        assertEquals("en", map["ENG"])
        assertEquals("zh", map["CHS"])
    }

    @Test
    fun sugoiLanguageCodeMapHasExactlyThreeEntries() {
        val ctx = Application()
        val translator = ExposedSugoiTranslator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertEquals(3, translator.exposedLanguageCodeMap.size)
    }

    // ─── JParaCrawl language code map ────────────────────────────────

    @Test
    fun jparacrawlLanguageCodeMapContainsExpectedMappings() {
        val ctx = Application()
        val translator = ExposedJparacrawlTranslator(ModelDownloadManager(ctx), OnnxSessionManager)
        val map = translator.exposedLanguageCodeMap

        // JParaCrawl supports JPN ↔ ENG bidirectional (no CHS)
        assertEquals("ja", map["JPN"])
        assertEquals("en", map["ENG"])
        assertEquals(2, map.size)
    }

    // ─── Translator not ready initially ──────────────────────────────

    @Test
    fun sugoiTranslatorNotReadyInitially() {
        val ctx = Application()
        val translator = SugoiTranslator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertFalse(translator.isReady)
    }

    @Test
    fun jparacrawlTranslatorNotReadyInitially() {
        val ctx = Application()
        val translator = JparacrawlTranslator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertFalse(translator.isReady)
    }

    @Test
    fun jparacrawlBigTranslatorNotReadyInitially() {
        val ctx = Application()
        val translator = JparacrawlBigTranslator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertFalse(translator.isReady)
    }
}
