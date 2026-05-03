package com.sakuravillager.manga_translator.translation.data

import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.translation.config.TranslationConfigMapper
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.InpainterType
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.RendererType
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import org.junit.Test
import org.junit.Assert.*

class TranslationConfigMapperTest {

    @Test
    fun `default preferences map to CTD detector`() {
        val config = TranslationConfigMapper.map(AppPreferences())
        assertEquals(DetectorType.CTD, config.detector.detector)
    }

    @Test
    fun `default preferences map to MODEL_48PX ocr`() {
        val config = TranslationConfigMapper.map(AppPreferences())
        assertEquals(OcrEngineType.MODEL_48PX, config.ocr.ocrEngine)
    }

    @Test
    fun `default preferences map to GPT_COMPATIBLE translator`() {
        val config = TranslationConfigMapper.map(AppPreferences())
        assertEquals(TranslatorType.GPT_COMPATIBLE, config.translator.translator)
    }

    @Test
    fun `default preferences map to LAMA_LARGE inpainter`() {
        val config = TranslationConfigMapper.map(AppPreferences())
        assertEquals(InpainterType.LAMA_LARGE, config.inpainter.inpainter)
    }

    @Test
    fun `default preferences map to DEFAULT renderer`() {
        val config = TranslationConfigMapper.map(AppPreferences())
        assertEquals(RendererType.DEFAULT, config.renderer.renderer)
    }

    @Test
    fun `unknown detector type falls back to CTD`() {
        val prefs = AppPreferences(detectorType = "unknown_detector")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(DetectorType.CTD, config.detector.detector)
    }

    @Test
    fun `unknown ocr type falls back to MODEL_48PX`() {
        val prefs = AppPreferences(ocrEngineType = "unknown_ocr")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(OcrEngineType.MODEL_48PX, config.ocr.ocrEngine)
    }

    @Test
    fun `unknown translator type falls back to GPT_COMPATIBLE`() {
        val prefs = AppPreferences(translatorType = "unknown_translator")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(TranslatorType.GPT_COMPATIBLE, config.translator.translator)
    }

    @Test
    fun `unknown inpainter type falls back to LAMA_LARGE`() {
        val prefs = AppPreferences(inpainterType = "unknown_inpainter")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(InpainterType.LAMA_LARGE, config.inpainter.inpainter)
    }

    @Test
    fun `custom targetLanguage is preserved in TranslationConfig`() {
        val prefs = AppPreferences(targetLanguage = "ENG")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals("ENG", config.translator.targetLanguage)
    }

    @Test
    fun `custom apiKey is preserved in TranslationConfig`() {
        val prefs = AppPreferences(apiKey = "test-api-key-123")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals("test-api-key-123", config.translator.apiKey)
    }

    @Test
    fun `custom apiBase is preserved in TranslationConfig`() {
        val prefs = AppPreferences(apiBase = "https://custom-api.example.com")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals("https://custom-api.example.com", config.translator.apiBase)
    }

    @Test
    fun `custom modelName is preserved as model in TranslationConfig`() {
        val prefs = AppPreferences(modelName = "gpt-4o")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals("gpt-4o", config.translator.model)
    }

    @Test
    fun `valid detector type ctd maps to CTD`() {
        val prefs = AppPreferences(detectorType = "ctd")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(DetectorType.CTD, config.detector.detector)
    }

    @Test
    fun `valid translator type gpt_compatible maps to GPT_COMPATIBLE`() {
        val prefs = AppPreferences(translatorType = "gpt_compatible")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(TranslatorType.GPT_COMPATIBLE, config.translator.translator)
    }

    @Test
    fun `valid translator type deepl maps to DEEPL`() {
        val prefs = AppPreferences(translatorType = "deepl")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(TranslatorType.DEEPL, config.translator.translator)
    }

    @Test
    fun `valid translator type none maps to NONE`() {
        val prefs = AppPreferences(translatorType = "none")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(TranslatorType.NONE, config.translator.translator)
    }

    @Test
    fun `valid detector type craft maps to CRAFT`() {
        val prefs = AppPreferences(detectorType = "craft")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(DetectorType.CRAFT, config.detector.detector)
    }

    @Test
    fun `valid inpainter type aot maps to AOT`() {
        val prefs = AppPreferences(inpainterType = "aot")
        val config = TranslationConfigMapper.map(prefs)
        assertEquals(InpainterType.AOT, config.inpainter.inpainter)
    }
}
