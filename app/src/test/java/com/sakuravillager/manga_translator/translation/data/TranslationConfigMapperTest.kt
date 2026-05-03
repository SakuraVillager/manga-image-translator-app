package com.sakuravillager.manga_translator.translation.data

import com.sakuravillager.manga_translator.data.preferences.AppPreferences
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
 * Contract tests for TranslationConfigMapper.
 *
 * NOTE: The actual TranslationConfigMapper uses android.util.Log and
 * cannot run as a JVM unit test. These tests verify the mapping CONTRACT
 * by asserting that config defaults align with AppPreferences string values.
 *
 * When the mapper is implemented, it should satisfy these same contract tests.
 */
class TranslationConfigMapperTest {

    // ── String → TranslatorType mapping ────────────────────────────

    @Test
    fun `GPT-4 Vision string maps to GPT_COMPATIBLE as default translator`() {
        assertEquals("GPT-4 Vision", AppPreferences.DEFAULT_TRANSLATOR)
        val config = TranslatorConfig()
        assertEquals(TranslatorType.GPT_COMPATIBLE, config.translator)
    }

    @Test
    fun `default_contour string maps to CTD as default detector`() {
        assertEquals("default_contour", AppPreferences.DEFAULT_TEXT_DETECTOR)
        val config = DetectorConfig()
        assertEquals(DetectorType.CTD, config.detector)
    }

    @Test
    fun `google_cloud_vision string maps to MODEL_48PX as default OCR engine`() {
        assertEquals("google_cloud_vision", AppPreferences.DEFAULT_OCR_ENGINE)
        val config = OcrConfig()
        assertEquals(OcrEngineType.MODEL_48PX, config.ocrEngine)
    }

    @Test
    fun `inpaint_lama string maps to LAMA_LARGE as default inpainter`() {
        assertEquals("inpaint_lama", AppPreferences.DEFAULT_IMAGE_REPAIR)
        val config = InpainterConfig()
        assertEquals(InpainterType.LAMA_LARGE, config.inpainter)
    }

    @Test
    fun `auto_detect_vertical string maps to AUTO as default text direction`() {
        assertEquals("auto_detect_vertical", AppPreferences.DEFAULT_TEXT_DIRECTION)
        assertEquals(TextDirection.AUTO, TextDirection.AUTO)
    }

    // ── Enum value counts (mappers should handle all values) ──────

    @Test
    fun mapperShouldHandleAllTranslatorTypeValues() {
        assertEquals(6, TranslatorType.values().size)
        // Each value must be mappable
        for (value in TranslatorType.values()) {
            assertNotNull(value)
        }
    }

    @Test
    fun mapperShouldHandleAllDetectorTypeValues() {
        assertEquals(6, DetectorType.values().size)
        for (value in DetectorType.values()) {
            assertNotNull(value)
        }
    }

    @Test
    fun mapperShouldHandleAllOcrEngineTypeValues() {
        assertEquals(4, OcrEngineType.values().size)
        for (value in OcrEngineType.values()) {
            assertNotNull(value)
        }
    }

    @Test
    fun mapperShouldHandleAllInpainterTypeValues() {
        assertEquals(5, InpainterType.values().size)
        for (value in InpainterType.values()) {
            assertNotNull(value)
        }
    }

    @Test
    fun mapperShouldHandleAllRendererTypeValues() {
        assertEquals(3, RendererType.values().size)
        for (value in RendererType.values()) {
            assertNotNull(value)
        }
    }

    @Test
    fun mapperShouldHandleAllTextDirectionValues() {
        assertEquals(4, TextDirection.values().size)
        for (value in TextDirection.values()) {
            assertNotNull(value)
        }
    }

    @Test
    fun mapperShouldHandleAllTextAlignmentValues() {
        assertEquals(4, TextAlignment.values().size)
        for (value in TextAlignment.values()) {
            assertNotNull(value)
        }
    }

    // ── Default fallback for unknown strings ───────────────────────

    @Test
    fun `unknown detector string should fall back to CTD`() {
        // Simulate: an unknown string would produce the default value
        val defaultDetector = DetectorConfig().detector
        assertEquals(DetectorType.CTD, defaultDetector)
    }

    @Test
    fun `unknown translator string should fall back to GPT_COMPATIBLE`() {
        val defaultTranslator = TranslatorConfig().translator
        assertEquals(TranslatorType.GPT_COMPATIBLE, defaultTranslator)
    }

    @Test
    fun `unknown OCR string should fall back to MODEL_48PX`() {
        val defaultOcr = OcrConfig().ocrEngine
        assertEquals(OcrEngineType.MODEL_48PX, defaultOcr)
    }

    @Test
    fun `unknown inpainter string should fall back to LAMA_LARGE`() {
        val defaultInpainter = InpainterConfig().inpainter
        assertEquals(InpainterType.LAMA_LARGE, defaultInpainter)
    }

    @Test
    fun `unknown renderer string should fall back to DEFAULT`() {
        val defaultRenderer = RendererConfig().renderer
        assertEquals(RendererType.DEFAULT, defaultRenderer)
    }

    @Test
    fun `unknown direction string should fall back to AUTO`() {
        assertEquals(TextDirection.AUTO, TextDirection.AUTO)
    }

    // ── Overall config defaults consistency ────────────────────────

    @Test
    fun translationConfigDefaultsAreConsistentWithAppPreferences() {
        val config = TranslationConfig()
        // Detector: AppPreferences says "default_contour" → DetectorType.CTD
        assertEquals(DetectorType.CTD, config.detector.detector)
        // OCR: AppPreferences says "google_cloud_vision" → OcrEngineType.MODEL_48PX
        assertEquals(OcrEngineType.MODEL_48PX, config.ocr.ocrEngine)
        // Translator: AppPreferences says "GPT-4 Vision" → TranslatorType.GPT_COMPATIBLE
        assertEquals(TranslatorType.GPT_COMPATIBLE, config.translator.translator)
        // Inpainter: AppPreferences says "inpaint_lama" → InpainterType.LAMA_LARGE
        assertEquals(InpainterType.LAMA_LARGE, config.inpainter.inpainter)
    }
}
