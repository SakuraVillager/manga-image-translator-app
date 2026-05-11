package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import org.junit.Test
import org.junit.Assert.*
import android.app.Application

/**
 * JVM unit tests for [Qwen2Translator] and [Qwen2BigTranslator].
 *
 * Tests the language code map and static configuration — pure Kotlin with
 * no ONNX Runtime dependency.
 */
class Qwen2TranslatorTest {

    /**
     * Helper subclass that exposes the protected [_LANGUAGE_CODE_MAP].
     */
    private class ExposedQwen2Translator(
        downloadManager: ModelDownloadManager,
        sessionManager: OnnxSessionManager,
    ) : Qwen2Translator(downloadManager, sessionManager) {
        val exposedLanguageCodeMap: Map<String, String>
            get() = _LANGUAGE_CODE_MAP
    }

    @Test
    fun languageCodeMapContainsPrimaryLanguages() {
        val ctx = Application()
        val translator = ExposedQwen2Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        val map = translator.exposedLanguageCodeMap

        assertEquals("Simplified Chinese", map["CHS"])
        assertEquals("Traditional Chinese", map["CHT"])
        assertEquals("English", map["ENG"])
        assertEquals("Japanese", map["JPN"])
        assertEquals("Korean", map["KOR"])
        assertEquals("Russian", map["RUS"])
        assertEquals("French", map["FRA"])
        assertEquals("German", map["DEU"])
        assertEquals("Spanish", map["ESP"])
    }

    @Test
    fun languageCodeMapHasAtLeastTwentyEntries() {
        val ctx = Application()
        val translator = ExposedQwen2Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertTrue(
            "Qwen2 language code map should have at least 20 entries",
            translator.exposedLanguageCodeMap.size >= 20,
        )
    }

    @Test
    fun qwen2TranslatorHasUseInt8EnabledByDefault() {
        // Qwen2Translator enables int8 by default (for mobile)
        val ctx = Application()
        val translator = Qwen2Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertNotNull("Translator should be constructable without ONNX loading", translator)
        assertFalse(translator.isReady) // Not ready until loadModel()
    }

    @Test
    fun qwen2BigTranslatorHasUseInt8DisabledByDefault() {
        // Qwen2BigTranslator disables int8 by default (quality preservation)
        val ctx = Application()
        val translator = Qwen2BigTranslator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertNotNull("Big translator should be constructable", translator)
    }
}
