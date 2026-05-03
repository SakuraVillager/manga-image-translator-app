package com.sakuravillager.manga_translator.translation.data

import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig
import com.sakuravillager.manga_translator.translation.data.config.InpainterType
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig
import com.sakuravillager.manga_translator.translation.data.config.RendererType
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import org.junit.Test
import org.junit.Assert.*

/**
 * JVM unit tests for translation config data models.
 *
 * All config classes are pure Kotlin with no Android dependencies,
 * so these run correctly as JVM unit tests.
 */
class TranslationConfigTest {

    // ── TranslationConfig ──────────────────────────────────────────

    @Test
    fun translationConfigDefaultValues() {
        val config = TranslationConfig()
        assertEquals(DetectorConfig(), config.detector)
        assertEquals(OcrConfig(), config.ocr)
        assertEquals(TranslatorConfig(), config.translator)
        assertEquals(InpainterConfig(), config.inpainter)
        assertEquals(RendererConfig(), config.renderer)
        assertEquals(3, config.kernelSize)
        assertEquals(20, config.maskDilationOffset)
        assertNull(config.filterText)
        assertNull(config.preDictPath)
        assertNull(config.postDictPath)
    }

    @Test
    fun translationConfigAcceptsCustomValues() {
        val config = TranslationConfig(
            kernelSize = 5,
            maskDilationOffset = 10,
            filterText = ".*test.*"
        )
        assertEquals(5, config.kernelSize)
        assertEquals(10, config.maskDilationOffset)
        assertEquals(".*test.*", config.filterText)
    }

    // ── DetectorConfig ─────────────────────────────────────────────

    @Test
    fun detectorConfigDefaultValues() {
        val config = DetectorConfig()
        assertEquals(DetectorType.CTD, config.detector)
        assertEquals(2048, config.detectionSize)
        assertEquals(0.5f, config.textThreshold, 0f)
        assertEquals(0.75f, config.boxThreshold, 0f)
        assertEquals(2.3f, config.unclipRatio, 0f)
        assertFalse(config.detRotate)
        assertFalse(config.detAutoRotate)
        assertFalse(config.detInvert)
        assertFalse(config.detGammaCorrect)
    }

    // ── TranslatorConfig ───────────────────────────────────────────

    @Test
    fun translatorConfigDefaultValues() {
        val config = TranslatorConfig()
        assertEquals(TranslatorType.GPT_COMPATIBLE, config.translator)
        assertEquals("CHS", config.targetLanguage)
        assertNull(config.skipLanguage)
        assertNull(config.apiKey)
        assertNull(config.apiBase)
        assertNull(config.model)
    }

    // ── OcrConfig ──────────────────────────────────────────────────

    @Test
    fun ocrConfigDefaultValues() {
        val config = OcrConfig()
        assertEquals(OcrEngineType.MODEL_48PX, config.ocrEngine)
        assertEquals(0, config.minTextLength)
        assertEquals(0, config.ignoreBubble)
    }

    // ── InpainterConfig ────────────────────────────────────────────

    @Test
    fun inpainterConfigDefaultValues() {
        val config = InpainterConfig()
        assertEquals(InpainterType.LAMA_LARGE, config.inpainter)
        assertEquals(2048, config.inpaintingSize)
    }

    // ── RendererConfig ─────────────────────────────────────────────

    @Test
    fun rendererConfigDefaultValues() {
        val config = RendererConfig()
        assertEquals(RendererType.DEFAULT, config.renderer)
        assertEquals(TextAlignment.AUTO, config.alignment)
        assertEquals(0, config.fontSizeOffset)
        assertEquals(-1, config.fontSizeMinimum)
        assertEquals(TextDirection.AUTO, config.direction)
        assertFalse(config.disableFontBorder)
        assertNull(config.fontColor)
        assertNull(config.lineSpacing)
        assertTrue(config.rtl)
    }

    // ── Sub-config accessibility ───────────────────────────────────

    @Test
    fun translationConfigSubConfigsAreAccessible() {
        val config = TranslationConfig()
        assertNotNull(config.detector)
        assertNotNull(config.ocr)
        assertNotNull(config.translator)
        assertNotNull(config.inpainter)
        assertNotNull(config.renderer)
    }

    @Test
    fun subConfigsCanBeReplacedIndependently() {
        val customDetector = DetectorConfig(
            detector = DetectorType.CRAFT,
            detectionSize = 1024
        )
        val config = TranslationConfig(detector = customDetector)
        assertEquals(DetectorType.CRAFT, config.detector.detector)
        assertEquals(1024, config.detector.detectionSize)
        // Other sub-configs remain at defaults
        assertEquals(TranslatorConfig(), config.translator)
    }

    // ── DetectorType enum ──────────────────────────────────────────

    @Test
    fun detectorTypeEnumHasSixValues() {
        assertEquals(6, DetectorType.values().size)
    }

    @Test
    fun detectorTypeEnumContainsAllExpectedValues() {
        assertContainsAll(
            DetectorType.values(),
            DetectorType.CTD,
            DetectorType.DEFAULT,
            DetectorType.DBCONVNEXT,
            DetectorType.CRAFT,
            DetectorType.PADDLE,
            DetectorType.NONE
        )
    }

    // ── TranslatorType enum ────────────────────────────────────────

    @Test
    fun translatorTypeEnumHasSixValues() {
        assertEquals(6, TranslatorType.values().size)
    }

    @Test
    fun translatorTypeEnumContainsAllExpectedValues() {
        assertContainsAll(
            TranslatorType.values(),
            TranslatorType.GPT_COMPATIBLE,
            TranslatorType.DEEPL,
            TranslatorType.BAIDU,
            TranslatorType.YOUDAO,
            TranslatorType.NONE,
            TranslatorType.ORIGINAL
        )
    }

    // ── OcrEngineType enum ─────────────────────────────────────────

    @Test
    fun ocrEngineTypeEnumHasFourValues() {
        assertEquals(4, OcrEngineType.values().size)
    }

    @Test
    fun ocrEngineTypeEnumContainsAllExpectedValues() {
        assertContainsAll(
            OcrEngineType.values(),
            OcrEngineType.MODEL_48PX,
            OcrEngineType.MODEL_32PX,
            OcrEngineType.MODEL_48PX_CTC,
            OcrEngineType.MOCR
        )
    }

    // ── InpainterType enum ─────────────────────────────────────────

    @Test
    fun inpainterTypeEnumHasFiveValues() {
        assertEquals(5, InpainterType.values().size)
    }

    @Test
    fun inpainterTypeEnumContainsAllExpectedValues() {
        assertContainsAll(
            InpainterType.values(),
            InpainterType.LAMA_LARGE,
            InpainterType.LAMA_MPE,
            InpainterType.AOT,
            InpainterType.SIMPLE_FILL,
            InpainterType.NONE
        )
    }

    // ── RendererType enum ──────────────────────────────────────────

    @Test
    fun rendererTypeEnumHasThreeValues() {
        assertEquals(3, RendererType.values().size)
    }

    @Test
    fun rendererTypeEnumContainsAllExpectedValues() {
        assertContainsAll(
            RendererType.values(),
            RendererType.DEFAULT,
            RendererType.MANGA2ENG,
            RendererType.NONE
        )
    }

    // ── Shared helper ──────────────────────────────────────────────

    private fun <T> assertContainsAll(values: Array<T>, vararg expected: T) {
        for (item in expected) {
            assertTrue("Missing expected value: $item", values.contains(item))
        }
    }
}
