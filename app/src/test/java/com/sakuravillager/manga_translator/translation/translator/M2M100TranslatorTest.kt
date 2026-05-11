package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import org.junit.Test
import org.junit.Assert.*
import android.app.Application

/**
 * JVM unit tests for [M2M100Translator] and [M2M100BigTranslator].
 *
 * Tests the language code map and basic metadata — pure Kotlin with no
 * ONNX Runtime dependency.
 */
class M2M100TranslatorTest {

    /**
     * Helper subclass that exposes the protected [_LANGUAGE_CODE_MAP].
     */
    private class ExposedM2M100Translator(
        downloadManager: ModelDownloadManager,
        sessionManager: OnnxSessionManager,
    ) : M2M100Translator(downloadManager, sessionManager) {
        val exposedLanguageCodeMap: Map<String, String>
            get() = _LANGUAGE_CODE_MAP
    }

    @Test
    fun languageCodeMapContainsPrimaryLanguages() {
        val ctx = Application()
        val translator = ExposedM2M100Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        val map = translator.exposedLanguageCodeMap

        assertEquals("zh", map["CHS"])
        assertEquals("zh", map["CHT"])     // Traditional Chinese falls back to zh
        assertEquals("en", map["ENG"])
        assertEquals("ja", map["JPN"])
        assertEquals("ko", map["KOR"])
        assertEquals("fr", map["FRA"])
        assertEquals("de", map["DEU"])
        assertEquals("ru", map["RUS"])
        assertEquals("vi", map["VIN"])
    }

    @Test
    fun languageCodeMapSizeIsAtLeastTwenty() {
        val ctx = Application()
        val translator = ExposedM2M100Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertTrue(
            "M2M100 language code map should have at least 20 entries",
            translator.exposedLanguageCodeMap.size >= 20,
        )
    }

    @Test
    fun translatorNotReadyInitially() {
        val ctx = Application()
        val translator = M2M100Translator(ModelDownloadManager(ctx), OnnxSessionManager)
        assertFalse(translator.isReady)
    }
}
